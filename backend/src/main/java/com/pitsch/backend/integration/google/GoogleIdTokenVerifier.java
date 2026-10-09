package com.pitsch.backend.integration.google;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.pitsch.backend.common.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Verifies Google-signed OIDC ID tokens (used by Pub/Sub authenticated push): RS256 signature against Google's
 * published JWKS (cached), issuer, audience, expiry and the expected service-account email.
 */
@Component
public class GoogleIdTokenVerifier {

    public record Claims(String email, boolean emailVerified, String audience, String subject) { }

    static final String JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Duration CACHE_TTL = Duration.ofHours(1);
    private static final Logger log = LoggerFactory.getLogger(GoogleIdTokenVerifier.class);

    private final RestClient rest;
    private final Json json;
    private final Clock clock;
    private final Map<String, PublicKey> keys = new ConcurrentHashMap<>();
    private volatile Instant fetchedAt = Instant.EPOCH;

    public GoogleIdTokenVerifier(Json json, Clock clock) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.rest = RestClient.builder().requestFactory(factory).build();
        this.json = json;
        this.clock = clock;
    }

    /** @return the claims if valid, otherwise null */
    public Claims verify(String token, String expectedAudience, String expectedEmail) {
        if (token == null) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        try {
            JsonNode header = json.read(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
            JsonNode claims = json.read(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            if (header == null || claims == null || !"RS256".equals(Json.text(header, "alg"))) {
                return null;
            }
            PublicKey key = key(Json.text(header, "kid"));
            if (key == null) {
                return null;
            }
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(key);
            sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!sig.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                return null;
            }
            String iss = Json.text(claims, "iss");
            long now = clock.instant().getEpochSecond();
            if (!"accounts.google.com".equals(iss) && !"https://accounts.google.com".equals(iss)) {
                return null;
            }
            if (claims.path("exp").asLong(0) < now - 60 || claims.path("iat").asLong(0) > now + 300) {
                return null;
            }
            String aud = Json.text(claims, "aud");
            if (expectedAudience != null && !expectedAudience.isBlank() && !expectedAudience.equals(aud)) {
                return null;
            }
            String email = Json.text(claims, "email");
            boolean verified = claims.path("email_verified").asBoolean(false);
            if (expectedEmail != null && !expectedEmail.isBlank() && (!expectedEmail.equalsIgnoreCase(email) || !verified)) {
                return null;
            }
            return new Claims(email, verified, aud, Json.text(claims, "sub"));
        } catch (Exception e) {
            log.debug("ID token rejected: {}", e.getClass().getSimpleName());
            return null;
        }
    }

    private PublicKey key(String kid) {
        if (kid == null) {
            return null;
        }
        PublicKey k = keys.get(kid);
        if (k == null || fetchedAt.plus(CACHE_TTL).isBefore(clock.instant())) {
            refresh();
            k = keys.get(kid);
        }
        return k;
    }

    private synchronized void refresh() {
        try {
            JsonNode jwks = rest.get().uri(JWKS_URL).retrieve().body(JsonNode.class);
            if (jwks == null) {
                return;
            }
            KeyFactory kf = KeyFactory.getInstance("RSA");
            for (JsonNode k : jwks.path("keys")) {
                BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode(k.path("n").asText()));
                BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode(k.path("e").asText()));
                keys.put(k.path("kid").asText(), kf.generatePublic(new RSAPublicKeySpec(n, e)));
            }
            fetchedAt = clock.instant();
        } catch (RestClientException | java.security.GeneralSecurityException | IllegalArgumentException e) {
            log.warn("Could not refresh Google signing keys: {}", e.getClass().getSimpleName());
        }
    }
}
