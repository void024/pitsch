package com.pitsch.backend.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.pitsch.backend.common.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Minimal HS256 JWT (header.payload.signature) using only the JDK — no extra dependency. */
@Component
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();
    private static final String HEADER = ENC.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private final byte[] secret;
    private final long ttlSeconds;
    private final Json json;
    private final Clock clock;

    public JwtService(@Value("${pitsch.jwt.secret}") String secret,
                      @Value("${pitsch.jwt.ttl-hours:24}") long ttlHours,
                      Json json, Clock clock) {
        if (secret.length() < 32) {
            throw new IllegalStateException("pitsch.jwt.secret / JWT_SECRET must be at least 32 characters");
        }
        if (secret.startsWith("dev-only-secret")) {
            log.warn("Using the development JWT secret. Set JWT_SECRET before deploying anywhere.");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlHours * 3600;
        this.json = json;
        this.clock = clock;
    }

    public String issue(User user) {
        long now = clock.instant().getEpochSecond();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", String.valueOf(user.getId()));
        claims.put("email", user.getEmail());
        claims.put("iat", now);
        claims.put("exp", now + ttlSeconds);
        String payload = ENC.encodeToString(json.write(claims).getBytes(StandardCharsets.UTF_8));
        String unsigned = HEADER + "." + payload;
        return unsigned + "." + ENC.encodeToString(sign(unsigned));
    }

    /** Returns the user id if the token is valid and unexpired, otherwise null. */
    public Long verify(String token) {
        if (token == null) {
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
            if (claims == null || claims.path("exp").asLong(0) < clock.instant().getEpochSecond()) {
                return null;
            }
            return Long.valueOf(claims.path("sub").asText());
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
