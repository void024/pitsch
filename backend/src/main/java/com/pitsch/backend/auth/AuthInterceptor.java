package com.pitsch.backend.auth;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.pitsch.backend.common.ErrorCode;
import com.pitsch.backend.common.GlobalExceptionHandler;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.common.RateLimiter;
import com.pitsch.backend.common.RequestContext;
import com.pitsch.backend.config.PitschProperties;
import com.pitsch.backend.org.Membership;
import com.pitsch.backend.org.MembershipRepository;
import com.pitsch.backend.org.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Default-deny authentication and authorization for /api/**.
 * <ol>
 *   <li>Endpoints annotated {@link PublicEndpoint} skip authentication (a valid token is still attached if present).</li>
 *   <li>Everything else needs a valid access token whose session is active in the database.</li>
 *   <li>The tenant is the session's active workspace; the role is read from the membership on every request.</li>
 *   <li>{@link RequiresPermission} is enforced against that role.</li>
 *   <li>Unverified users may read, but may only write through {@link AllowUnverified} endpoints.</li>
 * </ol>
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** Legacy request attribute (user ID) still read by a few older code paths. */
    public static final String USER_ID = "userId";
    private static final Duration LAST_SEEN_RESOLUTION = Duration.ofMinutes(5);

    private final JwtService jwt;
    private final SessionRepository sessions;
    private final UserRepository users;
    private final MembershipRepository memberships;
    private final RateLimiter rateLimiter;
    private final PitschProperties props;
    private final Json json;
    private final Clock clock;

    public AuthInterceptor(JwtService jwt, SessionRepository sessions, UserRepository users,
                           MembershipRepository memberships, RateLimiter rateLimiter, PitschProperties props,
                           Json json, Clock clock) {
        this.jwt = jwt;
        this.sessions = sessions;
        this.users = users;
        this.memberships = memberships;
        this.rateLimiter = rateLimiter;
        this.props = props;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || !(handler instanceof HandlerMethod method)) {
            return true;
        }
        boolean isPublic = annotated(method, PublicEndpoint.class);
        AuthPrincipal principal = resolve(request);
        if (isPublic) {
            if (principal != null) {
                attach(request, principal);
            }
            return true;
        }
        if (principal == null) {
            return deny(response, ErrorCode.UNAUTHENTICATED, "Please sign in again.");
        }
        if (!rateLimiter.tryAcquire("api:" + principal.userId(), props.getRateLimit().getApiPerMinute())) {
            return deny(response, ErrorCode.RATE_LIMITED, "Too many requests. Please slow down.");
        }
        if (principal.organizationId() == null && !annotated(method, AllowWithoutWorkspace.class)) {
            return deny(response, ErrorCode.FORBIDDEN, "Create or join a workspace first.");
        }
        boolean write = !"GET".equalsIgnoreCase(request.getMethod()) && !"HEAD".equalsIgnoreCase(request.getMethod());
        if (write && !principal.emailVerified() && props.getSecurity().isRequireEmailVerification()
                && !annotated(method, AllowUnverified.class)) {
            return deny(response, ErrorCode.EMAIL_NOT_VERIFIED, "Verify your email address to continue.");
        }
        RequiresPermission required = method.getMethodAnnotation(RequiresPermission.class);
        if (required == null) {
            required = method.getBeanType().getAnnotation(RequiresPermission.class);
        }
        if (required != null && !principal.has(required.value())) {
            return deny(response, ErrorCode.FORBIDDEN, "Your role does not allow this action.");
        }
        attach(request, principal);
        return true;
    }

    /** Resolves the caller from the bearer token + session row; null if anything is missing, invalid or revoked. */
    private AuthPrincipal resolve(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7).trim() : null;
        JwtService.AccessClaims claims = jwt.verify(token);
        if (claims == null) {
            return null;
        }
        Instant now = clock.instant();
        Session session = sessions.findById(claims.sessionId()).orElse(null);
        if (session == null || !session.isActive(now) || !session.getUserId().equals(claims.userId())) {
            return null;
        }
        User user = users.findById(claims.userId()).orElse(null);
        if (user == null) {
            return null;
        }
        Long orgId = session.getActiveOrganizationId();
        Role role = null;
        if (orgId != null) {
            Membership m = memberships.findByOrganizationIdAndUserId(orgId, user.getId()).orElse(null);
            if (m == null) {
                // Removed from the active workspace: fall back to another membership, if any.
                List<Membership> mine = memberships.findByUserIdOrderByCreatedAtAsc(user.getId());
                m = mine.isEmpty() ? null : mine.get(0);
                orgId = m == null ? null : m.getOrganizationId();
                session.setActiveOrganizationId(orgId);
                sessions.save(session);
            }
            role = m == null ? null : m.getRole();
        }
        if (session.getLastSeenAt() == null || session.getLastSeenAt().plus(LAST_SEEN_RESOLUTION).isBefore(now)) {
            session.setLastSeenAt(now);
            sessions.save(session);
        }
        return new AuthPrincipal(user.getId(), user.getEmail(), user.getName(), session.getId(), orgId, role,
                user.isEmailVerified());
    }

    private static void attach(HttpServletRequest request, AuthPrincipal principal) {
        request.setAttribute(AuthPrincipal.REQUEST_ATTRIBUTE, principal);
        request.setAttribute(USER_ID, principal.userId());
        MDC.put(RequestContext.USER_ID, String.valueOf(principal.userId()));
        if (principal.organizationId() != null) {
            MDC.put(RequestContext.ORGANIZATION_ID, String.valueOf(principal.organizationId()));
        }
    }

    private static boolean annotated(HandlerMethod method, Class<? extends java.lang.annotation.Annotation> type) {
        return method.hasMethodAnnotation(type) || method.getBeanType().isAnnotationPresent(type);
    }

    private boolean deny(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.write(GlobalExceptionHandler.errorMap(code, message)));
        return false;
    }
}
