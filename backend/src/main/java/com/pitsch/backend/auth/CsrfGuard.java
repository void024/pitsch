package com.pitsch.backend.auth;

import java.util.List;

import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.ErrorCode;
import com.pitsch.backend.config.PitschProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * The refresh/logout endpoints authenticate with a cookie, so they need CSRF protection. Two checks: a custom header
 * (cannot be sent cross-site without a CORS preflight, which only allowlisted origins pass) and, when the browser
 * sends one, an Origin from the CORS allowlist.
 */
@Component
public class CsrfGuard {

    public static final String HEADER = "X-Requested-With";

    private final List<String> allowedOrigins;

    public CsrfGuard(PitschProperties props) {
        this.allowedOrigins = props.getCors().getAllowedOrigins();
    }

    public void check(HttpServletRequest request) {
        if (request.getHeader(HEADER) == null) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Missing " + HEADER + " header.");
        }
        String origin = request.getHeader("Origin");
        if (origin != null && !allowedOrigins.contains(origin)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Origin not allowed.");
        }
    }
}
