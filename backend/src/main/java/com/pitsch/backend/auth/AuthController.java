package com.pitsch.backend.auth;

import java.util.List;
import java.util.Map;

import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.ClientInfo;
import com.pitsch.backend.common.ErrorResponse;
import com.pitsch.backend.common.RateLimiter;
import com.pitsch.backend.common.RequestContext;
import com.pitsch.backend.config.PitschProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication API. Responses carry a short-lived access token in the body; the rotating refresh token is set as an
 * httpOnly cookie and never exposed to scripts. Legacy paths (/api/auth/*) are kept for older clients.
 */
@RestController
public class AuthController {

    public record LoginRequest(@NotBlank @Email String email, @NotBlank @Size(max = 200) String password) { }

    public record SignupRequest(@NotBlank @Size(max = 120) String name, @NotBlank @Email @Size(max = 320) String email,
                                @NotBlank @Size(max = 200) String password, @Size(max = 200) String workspaceName,
                                @Size(max = 64) String timezone, @Size(max = 200) String inviteToken) { }

    public record TokenRequest(@NotBlank @Size(max = 200) String token) { }

    public record EmailRequest(@NotBlank @Email @Size(max = 320) String email) { }

    public record ResetRequest(@NotBlank @Size(max = 200) String token, @NotBlank @Size(max = 200) String password) { }

    public record ChangePasswordRequest(@Size(max = 200) String currentPassword, @NotBlank @Size(max = 200) String newPassword) { }

    public record SwitchRequest(@NotNull Long organizationId) { }

    /** {@code token} duplicates {@code accessToken} for the legacy frontend contract. */
    public record AuthResponse(String accessToken, String token, String tokenType, long expiresIn, UserView user,
                               MeService.MeView me) { }

    private final AuthService auth;
    private final SessionService sessions;
    private final SessionCookies cookies;
    private final CsrfGuard csrf;
    private final MeService meService;
    private final RateLimiter rateLimiter;
    private final PitschProperties props;

    public AuthController(AuthService auth, SessionService sessions, SessionCookies cookies, CsrfGuard csrf,
                          MeService meService, RateLimiter rateLimiter, PitschProperties props) {
        this.auth = auth;
        this.sessions = sessions;
        this.cookies = cookies;
        this.csrf = csrf;
        this.meService = meService;
        this.rateLimiter = rateLimiter;
        this.props = props;
    }

    @PublicEndpoint
    @PostMapping({"/api/v1/auth/signup", "/api/auth/signup"})
    public ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest req) {
        limitAuth("signup");
        SessionService.Issued issued = auth.signup(new AuthService.SignupCommand(req.name(), req.email(), req.password(),
                req.workspaceName(), req.timezone(), req.inviteToken()), ClientInfo.current());
        return respond(HttpStatus.CREATED, issued);
    }

    @PublicEndpoint
    @PostMapping({"/api/v1/auth/login", "/api/auth/login", "/api/auth/signin"})
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        limitAuth("login");
        rateLimiter.check("login-email:" + req.email().trim().toLowerCase(), props.getRateLimit().getAuthPerMinute());
        return respond(HttpStatus.OK, auth.login(req.email(), req.password(), ClientInfo.current()));
    }

    @PublicEndpoint
    @PostMapping("/api/v1/auth/refresh")
    public ResponseEntity<?> refresh(HttpServletRequest request,
                                     @CookieValue(name = SessionCookies.REFRESH_COOKIE, required = false) String refreshToken) {
        csrf.check(request);
        limitAuth("refresh");
        try {
            return respond(HttpStatus.OK, sessions.rotate(refreshToken, ClientInfo.current()));
        } catch (ApiException e) {
            // Clear a dead cookie so the browser stops presenting it.
            return ResponseEntity.status(e.getStatus()).header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
                    .body(new ErrorResponse(e.getCode().name(), e.getMessage(), RequestContext.requestId(), null,
                            e.getStatus().value()));
        }
    }

    @PublicEndpoint
    @PostMapping({"/api/v1/auth/logout", "/api/auth/logout"})
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request,
                                                      @CookieValue(name = SessionCookies.REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken != null) {
            csrf.check(request);
        }
        sessions.revokeByRefreshToken(refreshToken);
        Object principal = request.getAttribute(AuthPrincipal.REQUEST_ATTRIBUTE);
        if (principal instanceof AuthPrincipal p) {
            sessions.revokeById(p.userId(), p.sessionId());
        }
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookies.clear().toString()).body(Map.of("ok", true));
    }

    @AllowWithoutWorkspace
    @GetMapping({"/api/v1/auth/me", "/api/v1/me"})
    public MeService.MeView me(AuthPrincipal principal) {
        return meService.me(principal.userId(), principal.organizationId());
    }

    /** Legacy shape: just the user. */
    @AllowWithoutWorkspace
    @GetMapping("/api/auth/me")
    public UserView legacyMe(AuthPrincipal principal) {
        return meService.me(principal.userId(), principal.organizationId()).user();
    }

    @PublicEndpoint
    @PostMapping("/api/v1/auth/verify-email")
    public Map<String, Object> verifyEmail(@Valid @RequestBody TokenRequest req) {
        limitAuth("verify");
        auth.confirmEmailToken(req.token());
        return Map.of("ok", true);
    }

    @AllowUnverified
    @AllowWithoutWorkspace
    @PostMapping("/api/v1/auth/resend-verification")
    public ResponseEntity<Map<String, Object>> resendVerification(AuthPrincipal principal) {
        rateLimiter.check("resend:" + principal.userId(), 3);
        auth.resendVerification(principal.userId());
        return ResponseEntity.accepted().body(Map.of("ok", true));
    }

    @PublicEndpoint
    @PostMapping("/api/v1/auth/forgot-password")
    public ResponseEntity<Map<String, Object>> forgotPassword(@Valid @RequestBody EmailRequest req) {
        limitAuth("forgot");
        auth.forgotPassword(req.email());
        return ResponseEntity.accepted().body(Map.of("ok", true,
                "message", "If an account exists for that address, we've sent a reset link."));
    }

    @PublicEndpoint
    @PostMapping("/api/v1/auth/reset-password")
    public Map<String, Object> resetPassword(@Valid @RequestBody ResetRequest req) {
        limitAuth("reset");
        auth.resetPassword(req.token(), req.password());
        return Map.of("ok", true);
    }

    @AllowUnverified
    @AllowWithoutWorkspace
    @PostMapping("/api/v1/auth/change-password")
    public Map<String, Object> changePassword(AuthPrincipal principal, @Valid @RequestBody ChangePasswordRequest req) {
        rateLimiter.check("change-password:" + principal.userId(), 5);
        auth.changePassword(principal, req.currentPassword(), req.newPassword());
        return Map.of("ok", true);
    }

    @AllowWithoutWorkspace
    @GetMapping("/api/v1/auth/sessions")
    public List<Map<String, Object>> sessions(AuthPrincipal principal) {
        return sessions.active(principal.userId()).stream().map(s -> sessions.describe(s, principal.sessionId())).toList();
    }

    @AllowUnverified
    @AllowWithoutWorkspace
    @DeleteMapping("/api/v1/auth/sessions/{sessionId}")
    public ResponseEntity<Void> revokeSession(AuthPrincipal principal, @PathVariable String sessionId) {
        sessions.revokeById(principal.userId(), sessionId);
        return ResponseEntity.noContent().build();
    }

    @AllowUnverified
    @AllowWithoutWorkspace
    @PostMapping("/api/v1/auth/switch-workspace")
    public MeService.MeView switchWorkspace(AuthPrincipal principal, @Valid @RequestBody SwitchRequest req) {
        auth.switchWorkspace(principal, req.organizationId());
        return meService.me(principal.userId(), req.organizationId());
    }

    private void limitAuth(String action) {
        rateLimiter.check("auth:" + action + ":" + ClientInfo.current().ip(), props.getRateLimit().getAuthPerMinute());
    }

    private ResponseEntity<AuthResponse> respond(HttpStatus status, SessionService.Issued issued) {
        MeService.MeView me = meService.me(issued.session().getUserId(), issued.session().getActiveOrganizationId());
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, cookies.refresh(issued.refreshToken()).toString())
                .body(new AuthResponse(issued.accessToken(), issued.accessToken(), "Bearer", issued.expiresInSeconds(),
                        me.user(), me));
    }
}
