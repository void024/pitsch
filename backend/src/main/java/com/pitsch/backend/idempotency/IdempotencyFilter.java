package com.pitsch.backend.idempotency;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Pattern;

import com.pitsch.backend.auth.JwtService;
import com.pitsch.backend.common.ErrorCode;
import com.pitsch.backend.common.GlobalExceptionHandler;
import com.pitsch.backend.common.Hashing;
import com.pitsch.backend.common.Json;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * HTTP-level idempotency for authenticated POST requests carrying an {@code Idempotency-Key} header (Stripe-style):
 * <ul>
 *   <li>first request: recorded as in-flight, executed, and its response (status &lt; 500) stored for 24 hours;</li>
 *   <li>retry with the same key and body: the stored response is replayed with {@code Idempotent-Replayed: true};</li>
 *   <li>same key, different body: 422 IDEMPOTENCY_KEY_REUSED; same key while the first is still running: 409 CONFLICT.</li>
 * </ul>
 * 5xx outcomes are not stored, so the client may retry. Multipart and bodies over 1 MB are passed through without
 * idempotency (side effects behind them are protected by the domain-level keys in approvals/external_operations).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9:._-]{8,100}$");
    private static final int MAX_BODY = 1024 * 1024;
    private static final int MAX_STORED_RESPONSE = 256 * 1024;
    private static final Duration TTL = Duration.ofHours(24);

    private final IdempotencyRecordRepository records;
    private final JwtService jwt;
    private final TransactionTemplate tx;
    private final Json json;
    private final Clock clock;

    public IdempotencyFilter(IdempotencyRecordRepository records, JwtService jwt, PlatformTransactionManager txManager,
                             Json json, Clock clock) {
        this.records = records;
        this.jwt = jwt;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.json = json;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String key = request.getHeader(HEADER);
        String type = request.getContentType();
        return key == null || !"POST".equalsIgnoreCase(request.getMethod())
                || !request.getRequestURI().startsWith("/api/")
                || (type != null && type.toLowerCase().startsWith("multipart/"))
                || request.getContentLengthLong() > MAX_BODY;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        if (!KEY.matcher(key).matches()) {
            deny(response, ErrorCode.VALIDATION_FAILED, HEADER + " must be 8-100 characters of [A-Za-z0-9:._-].");
            return;
        }
        String auth = request.getHeader("Authorization");
        JwtService.AccessClaims claims = jwt.verify(auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : null);
        if (claims == null) {
            chain.doFilter(request, response);   // unauthenticated: the interceptor answers 401
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            deny(response, ErrorCode.PAYLOAD_TOO_LARGE, "Requests with " + HEADER + " are limited to 1 MB.");
            return;
        }
        String path = request.getRequestURI();
        String hash = Hashing.sha256Hex((request.getMethod() + " " + path + "\n" + new String(body, StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8));
        Long userId = claims.userId();

        IdempotencyRecord existing = tx.execute(s -> records.findByUserIdAndKey(userId, key).orElse(null));
        if (existing != null && existing.getExpiresAt().isBefore(clock.instant())) {
            tx.executeWithoutResult(s -> records.deleteById(existing.getId()));
        } else if (existing != null) {
            if (!existing.getRequestHash().equals(hash)) {
                deny(response, ErrorCode.IDEMPOTENCY_KEY_REUSED, "This " + HEADER + " was already used for a different request.");
            } else if (existing.getResponseStatus() == null) {
                deny(response, ErrorCode.CONFLICT, "A request with this " + HEADER + " is still being processed.");
            } else {
                response.setStatus(existing.getResponseStatus());
                response.setHeader("Idempotent-Replayed", "true");
                if (existing.getResponseBody() != null) {
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write(existing.getResponseBody());
                }
            }
            return;
        }

        Long recordId;
        try {
            recordId = tx.execute(s -> {
                IdempotencyRecord r = new IdempotencyRecord();
                r.setUserId(userId);
                r.setKey(key);
                r.setMethod(request.getMethod());
                r.setPath(path.length() > 300 ? path.substring(0, 300) : path);
                r.setRequestHash(hash);
                r.setCreatedAt(clock.instant());
                r.setExpiresAt(clock.instant().plus(TTL));
                return records.save(r).getId();
            });
        } catch (DataIntegrityViolationException e) {
            deny(response, ErrorCode.CONFLICT, "A request with this " + HEADER + " is still being processed.");
            return;
        }

        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        boolean completed = false;
        try {
            chain.doFilter(new CachedBodyRequest(request, body), wrapped);
            completed = true;
        } finally {
            int status = wrapped.getStatus();
            byte[] out = wrapped.getContentAsByteArray();
            try {
                if (completed && status < 500 && out.length <= MAX_STORED_RESPONSE) {
                    String text = out.length == 0 ? null : new String(out, StandardCharsets.UTF_8);
                    tx.executeWithoutResult(s -> records.findById(recordId).ifPresent(r -> {
                        r.setResponseStatus(status);
                        r.setResponseBody(text);
                        records.save(r);
                    }));
                } else {
                    tx.executeWithoutResult(s -> records.deleteById(recordId));
                }
            } catch (RuntimeException e) {
                log.warn("Could not store idempotent response for key on {}", path);
            }
            wrapped.copyBodyToResponse();
        }
    }

    @Scheduled(cron = "0 29 * * * *")
    public void purgeExpired() {
        tx.executeWithoutResult(s -> records.deleteExpired(clock.instant()));
    }

    private void deny(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.write(GlobalExceptionHandler.errorMap(code, message)));
    }

    /** Re-readable request whose body was consumed for hashing. */
    static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
