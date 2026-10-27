package com.pitsch.backend.auth;

import java.time.Duration;

import com.pitsch.backend.config.PitschProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The refresh token lives only in an httpOnly, Secure, SameSite cookie scoped to the auth endpoints, so page scripts
 * (and therefore XSS) can never read it. The access token is kept in memory by the SPA.
 */
@Component
public class SessionCookies {

    public static final String REFRESH_COOKIE = "pitsch_refresh";
    public static final String PATH = "/api/v1/auth";

    private final PitschProperties.Security security;

    public SessionCookies(PitschProperties props) {
        this.security = props.getSecurity();
    }

    public ResponseCookie refresh(String token) {
        return base(token).maxAge(Duration.ofDays(security.getRefreshTokenDays())).build();
    }

    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(security.isCookieSecure())
                .sameSite(security.getCookieSameSite())
                .path(PATH);
        if (security.getCookieDomain() != null && !security.getCookieDomain().isBlank()) {
            b.domain(security.getCookieDomain());
        }
        return b;
    }
}
