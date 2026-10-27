package com.pitsch.backend.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.pitsch.backend.audit.AuditAction;
import com.pitsch.backend.audit.AuditService;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.ClientInfo;
import com.pitsch.backend.common.ErrorCode;
import com.pitsch.backend.common.Hashing;
import com.pitsch.backend.config.PitschProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Device sessions and refresh-token rotation with reuse detection. A refresh token is single-use: presenting a
 * rotated token again (outside a short race window for parallel tabs) revokes the whole session.
 */
@Service
public class SessionService {

    public record Issued(Session session, String refreshToken, String accessToken, long expiresInSeconds) { }

    static final Duration ABSOLUTE_MAX = Duration.ofDays(90);

    private final SessionRepository sessions;
    private final RefreshTokenRepository tokens;
    private final JwtService jwt;
    private final AuditService audit;
    private final Clock clock;
    private final Duration refreshTtl;
    private final Duration rotationGrace;

    public SessionService(SessionRepository sessions, RefreshTokenRepository tokens, JwtService jwt, AuditService audit,
                          PitschProperties props, Clock clock) {
        this.sessions = sessions;
        this.tokens = tokens;
        this.jwt = jwt;
        this.audit = audit;
        this.clock = clock;
        this.refreshTtl = Duration.ofDays(Math.max(1, props.getSecurity().getRefreshTokenDays()));
        this.rotationGrace = Duration.ofSeconds(Math.max(0, Math.min(60, props.getSecurity().getRefreshReuseGraceSeconds())));
    }

    @Transactional
    public Issued create(Long userId, Long activeOrganizationId, ClientInfo client) {
        Instant now = clock.instant();
        Session s = new Session();
        s.setId(UUID.randomUUID().toString());
        s.setUserId(userId);
        s.setActiveOrganizationId(activeOrganizationId);
        s.setUserAgent(client.userAgent());
        s.setIpAddress(client.ip());
        s.setCreatedAt(now);
        s.setLastSeenAt(now);
        s.setExpiresAt(now.plus(refreshTtl));
        sessions.save(s);
        String refresh = newRefreshToken(s.getId(), now);
        return new Issued(s, refresh, jwt.issue(userId, s.getId()), jwt.ttlSeconds());
    }

    /** Exchanges a refresh token for a new access token and a new refresh token. */
    @Transactional(noRollbackFor = ApiException.class)
    public Issued rotate(String rawRefreshToken, ClientInfo client) {
        Instant now = clock.instant();
        RefreshToken token = tokens.findByTokenHash(Hashing.sha256Hex(rawRefreshToken == null ? "" : rawRefreshToken))
                .orElseThrow(() -> new ApiException(ErrorCode.SESSION_EXPIRED, "Please sign in again."));
        Session session = sessions.findById(token.getSessionId())
                .orElseThrow(() -> new ApiException(ErrorCode.SESSION_EXPIRED, "Please sign in again."));
        if (!session.isActive(now) || token.getRevokedAt() != null || token.getExpiresAt().isBefore(now)) {
            throw new ApiException(ErrorCode.SESSION_EXPIRED, "Please sign in again.");
        }
        if (token.getRotatedAt() != null) {
            if (token.getRotatedAt().plus(rotationGrace).isBefore(now)) {
                revoke(session, "REFRESH_REUSE");
                audit.recordIndependent(session.getActiveOrganizationId(), session.getUserId(),
                        AuditAction.REFRESH_TOKEN_REUSE, "SESSION", session.getId(), null);
                throw new ApiException(ErrorCode.SESSION_EXPIRED, "Please sign in again.");
            }
            // Parallel refresh from another tab within the grace window: issue a sibling token, keep the session.
        } else {
            token.setRotatedAt(now);
            tokens.save(token);
        }
        Instant extended = now.plus(refreshTtl);
        Instant cap = session.getCreatedAt().plus(ABSOLUTE_MAX);
        session.setExpiresAt(extended.isAfter(cap) ? cap : extended);
        session.setLastSeenAt(now);
        if (client.ip() != null) {
            session.setIpAddress(client.ip());
        }
        sessions.save(session);
        String refresh = newRefreshToken(session.getId(), now);
        return new Issued(session, refresh, jwt.issue(session.getUserId(), session.getId()), jwt.ttlSeconds());
    }

    @Transactional
    public void revokeByRefreshToken(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        tokens.findByTokenHash(Hashing.sha256Hex(rawRefreshToken))
                .flatMap(t -> sessions.findById(t.getSessionId()))
                .ifPresent(s -> revoke(s, "LOGOUT"));
    }

    @Transactional
    public void revoke(Session session, String reason) {
        if (session.getRevokedAt() == null) {
            session.setRevokedAt(clock.instant());
            session.setRevokeReason(reason);
            sessions.save(session);
        }
    }

    @Transactional
    public void revokeById(Long userId, String sessionId) {
        Session s = sessions.findById(sessionId).filter(x -> x.getUserId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Session"));
        revoke(s, "USER_REVOKED");
        audit.record(s.getActiveOrganizationId(), userId, AuditAction.SESSION_REVOKED, "SESSION", sessionId, null);
    }

    @Transactional
    public int revokeOthers(Long userId, String keepSessionId, String reason) {
        return sessions.revokeAllExcept(userId, keepSessionId == null ? "" : keepSessionId, clock.instant(), reason);
    }

    @Transactional(readOnly = true)
    public List<Session> active(Long userId) {
        return sessions.findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByLastSeenAtDesc(userId, clock.instant());
    }

    @Transactional
    public void switchOrganization(String sessionId, Long organizationId) {
        Session s = sessions.findById(sessionId).orElseThrow(() -> ApiException.notFound("Session"));
        s.setActiveOrganizationId(organizationId);
        sessions.save(s);
    }

    /** Housekeeping: drop sessions that expired more than 30 days ago (their refresh tokens cascade). */
    @Scheduled(cron = "0 17 3 * * *")
    @Transactional
    public void purgeExpired() {
        sessions.deleteExpiredBefore(clock.instant().minus(Duration.ofDays(30)));
    }

    public Map<String, Object> describe(Session s, String currentSessionId) {
        return Map.of("id", s.getId(), "current", s.getId().equals(currentSessionId),
                "userAgent", s.getUserAgent() == null ? "" : s.getUserAgent(),
                "ipAddress", s.getIpAddress() == null ? "" : s.getIpAddress(),
                "createdAt", s.getCreatedAt(), "lastSeenAt", s.getLastSeenAt() == null ? s.getCreatedAt() : s.getLastSeenAt());
    }

    private String newRefreshToken(String sessionId, Instant now) {
        String raw = Hashing.randomToken();
        RefreshToken t = new RefreshToken();
        t.setSessionId(sessionId);
        t.setTokenHash(Hashing.sha256Hex(raw));
        t.setCreatedAt(now);
        t.setExpiresAt(now.plus(refreshTtl));
        tokens.save(t);
        return raw;
    }
}
