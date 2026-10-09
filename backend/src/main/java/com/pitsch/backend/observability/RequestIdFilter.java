package com.pitsch.backend.observability;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import com.pitsch.backend.common.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns every request a correlation ID (accepting a well-formed incoming {@code X-Request-Id} from the edge),
 * exposes it in the MDC for structured logs and echoes it in the response header and in error bodies.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String requestId = incoming != null && SAFE_ID.matcher(incoming).matches()
                ? incoming : UUID.randomUUID().toString();
        String userAgent = request.getHeader("User-Agent");
        MDC.put(RequestContext.REQUEST_ID, requestId);
        MDC.put(RequestContext.CLIENT_IP, request.getRemoteAddr());
        if (userAgent != null) {
            MDC.put(RequestContext.USER_AGENT, userAgent.length() > 400 ? userAgent.substring(0, 400) : userAgent);
        }
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
