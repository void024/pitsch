package com.pitsch.backend.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.pitsch.backend.common.Hashing;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.config.PitschProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Short-lived HS256 access tokens. Claims: iss, aud, sub (user ID), sid (session ID), iat, exp, jti.
 * Tenant scope is NOT a claim: it is read from the session row on every request, so it can never be forged and a
 * workspace switch or membership removal takes effect immediately.
 */
@Component
public class JwtService {

    public record AccessClaims(Long userId, String sessionId, long expiresAt) { }

    public static final String AUDIENCE = "pitsch-api";
    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();
    private static final String HEADER = ENC.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    static final String DEV_SECRET_PREFIX = "dev-only-secret";

    private final byte[] secret;
    private final long ttlSeconds;
    private final String issuer;
    private final Json json;
    private final Clock clock;

    public JwtService(PitschProperties props, Json json, Clock clock) {
        String configured = props.getSecurity().getJwtSecret();
        if (configured == null || configured.isBlank()) {
            if (props.isProduction()) {
                throw new IllegalStateException("JWT_SECRET is required in production");
            }
            configured = Hashing.randomToken() + Hashing.randomToken();
            log.warn("JWT_SECRET not set: generated an ephemeral secret (sessions end when the backend restarts).");
        }
        if (configured.length() < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 characters");
        }
        this.secret = configured.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = props.getSecurity().getAccessTokenMinutes() * 60L;
        this.issuer = props.getSecurity().getJwtIssuer();
        this.json = json;
        this.clock = clock;
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    public String issue(Long userId, String sessionId) {
        long now = clock.instant().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer);
        claims.put("aud", AUDIENCE);
        claims.put("sub", String.valueOf(userId));
        claims.put("sid", sessionId);
        claims.put("iat", now);
        claims.put("exp", now + ttlSeconds);
        claims.put("jti", UUID.randomUUID().toString());
        String payload = ENC.encodeToString(json.write(claims).getBytes(StandardCharsets.UTF_8));
        String unsigned = HEADER + "." + payload;
        return unsigned + "." + ENC.encodeToString(sign(unsigned));
    }

    /** Returns the claims if the token is well-formed, correctly signed, unexpired and for this issuer/audience. */
    public AccessClaims verify(String token) {
        if (token == null || token.length() > 4096) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3 || !HEADER.equals(parts[0])) {
            return null;
        }
        try {
            byte[] expected = sign(parts[0] + "." + parts[1]);
            if (!MessageDigest.isEqual(expected, DEC.decode(parts[2]))) {
                return null;
            }
            JsonNode claims = json.read(new String(DEC.decode(parts[1]), StandardCharsets.UTF_8));
            long exp = claims == null ? 0 : claims.path("exp").asLong(0);
            if (exp < clock.instant().getEpochSecond()
                    || !issuer.equals(claims.path("iss").asText())
                    || !AUDIENCE.equals(claims.path("aud").asText())
                    || claims.path("sid").asText().isBlank()) {
                return null;
            }
            return new AccessClaims(Long.valueOf(claims.path("sub").asText()), claims.path("sid").asText(), exp);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return null;
        }
    }

    private byte[] sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }
}
