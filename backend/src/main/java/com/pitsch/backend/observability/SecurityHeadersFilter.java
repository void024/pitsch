package com.pitsch.backend.observability;

import java.io.IOException;

import com.pitsch.backend.config.PitschProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Defensive HTTP headers for a JSON API (the SPA is served separately with its own CSP). */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    private final boolean production;

    public SecurityHeadersFilter(PitschProperties props) {
        this.production = props.isProduction();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        String path = request.getRequestURI();
        if (path.startsWith("/api/docs") || path.startsWith("/swagger-ui")) {
            response.setHeader("Content-Security-Policy",
                    "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self'");
        } else {
            response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        }
        if (path.startsWith("/api/")) {
            response.setHeader("Cache-Control", "no-store");
        }
        if (production) {
            response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
        chain.doFilter(request, response);
    }
}
