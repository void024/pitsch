package com.pitsch.backend.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pitsch.backend.auth.JwtService;
import com.pitsch.backend.auth.PasswordHasher;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.config.PitschProperties;
import com.pitsch.backend.config.ProductionConfigValidator;
import com.pitsch.backend.security.TokenCipher;
import org.junit.jupiter.api.Test;

/** Pure unit tests of the security building blocks (no Spring context). */
class SecurityUnitTests {

    static PitschProperties props(String mode) {
        PitschProperties p = new PitschProperties();
        p.setMode(mode);
        p.getSecurity().setJwtSecret("unit-test-secret-0123456789abcdefghijklmnopqrstuvwxyz");
        p.getSecurity().setPasswordHashIterations(100_000);
        return p;
    }

    @Test
    void tokenCipherRoundTripsAndRotatesKeys() {
        PitschProperties p = props("test");
        p.getSecurity().setEncryptionKeys("k2:MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=,k1:YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=");
        TokenCipher cipher = new TokenCipher(p);
        String enc = cipher.encrypt("refresh-token-value");
        assertTrue(enc.startsWith("v1:k2:"), enc);
        assertFalse(enc.contains("refresh-token-value"));
        assertEquals("refresh-token-value", cipher.decrypt(enc));
        assertNotEquals(enc, cipher.encrypt("refresh-token-value"), "random IV per encryption");

        // A value encrypted with the old key (k1 active) is still readable after rotation and flagged for re-encryption.
        PitschProperties old = props("test");
        old.getSecurity().setEncryptionKeys("k1:YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=");
        String legacy = new TokenCipher(old).encrypt("older");
        assertEquals("older", cipher.decrypt(legacy));
        assertTrue(cipher.needsReencryption(legacy));

        // Tampering is detected (GCM tag).
        String[] parts = enc.split(":", 3);
        byte[] raw = java.util.Base64.getDecoder().decode(parts[2]);
        raw[raw.length / 2] ^= 0x01;
        String tampered = parts[0] + ":" + parts[1] + ":" + java.util.Base64.getEncoder().encodeToString(raw);
        assertThrows(IllegalStateException.class, () -> cipher.decrypt(tampered));
    }

    @Test
    void tokenCipherRefusesMissingKeysInProduction() {
        PitschProperties p = props("production");
        assertThrows(IllegalStateException.class, () -> new TokenCipher(p));
        p.getSecurity().setEncryptionKeys("k1:dG9vLXNob3J0");
        assertThrows(IllegalStateException.class, () -> new TokenCipher(p));
    }

    @Test
    void passwordHashingIsSaltedAndVerifiable() {
        PasswordHasher h = new PasswordHasher(props("test"));
        String a = h.hash("correct-horse-42");
        assertNotEquals(a, h.hash("correct-horse-42"));
        assertTrue(h.matches("correct-horse-42", a));
        assertFalse(h.matches("correct-horse-43", a));
        assertFalse(h.matches("anything", PasswordHasher.NO_PASSWORD));
        assertNull(PasswordHasher.policyViolation("a-good-pass-1", "x@y.z"));
        assertTrue(PasswordHasher.policyViolation("short1", "x@y.z") != null);
        assertTrue(PasswordHasher.policyViolation("allletterspassword", "x@y.z") != null);
    }

    @Test
    void jwtRejectsTamperingExpiryAndForeignAlgorithms() {
        Json json = new Json(new ObjectMapper());
        Instant t0 = Instant.parse("2030-01-01T00:00:00Z");
        JwtService jwt = new JwtService(props("test"), json, Clock.fixed(t0, ZoneOffset.UTC));
        String token = jwt.issue(42L, "session-1");
        JwtService.AccessClaims claims = jwt.verify(token);
        assertEquals(42L, claims.userId());
        assertEquals("session-1", claims.sessionId());

        String[] parts = token.split("\\.");
        String forged = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "xx." + parts[2];
        assertNull(jwt.verify(forged));
        assertNull(jwt.verify(parts[0] + "." + parts[1] + "."));
        assertNull(jwt.verify("eyJhbGciOiJub25lIn0." + parts[1] + "."));
        assertNull(jwt.verify(null));

        JwtService later = new JwtService(props("test"), json, Clock.fixed(t0.plusSeconds(3600), ZoneOffset.UTC));
        assertNull(later.verify(token), "expired after 15 minutes");

        PitschProperties other = props("test");
        other.getSecurity().setJwtSecret("another-secret-0123456789abcdefghijklmnopqrstuvwxyz");
        assertNull(new JwtService(other, json, Clock.fixed(t0, ZoneOffset.UTC)).verify(token));
    }

    @Test
    void productionRefusesInsecureConfiguration() {
        PitschProperties p = new PitschProperties();
        p.setMode("production");
        List<String> problems = ProductionConfigValidator.validate(p, "jdbc:h2:file:./data/pitsch", "", false);
        String all = String.join("\n", problems);
        assertTrue(all.contains("JWT_SECRET"), all);
        assertTrue(all.contains("PITSCH_ENCRYPTION_KEYS"), all);
        assertTrue(all.contains("PostgreSQL"), all);
        assertTrue(all.contains("AI_SERVICE_TOKEN"), all);
        assertTrue(all.contains("SMTP_HOST"), all);
        assertTrue(all.contains("S3_BUCKET"), all);
        assertTrue(all.contains("https"), all);
        assertFalse(all.contains("unit-test-secret"), "never echo secret values");
    }

    @Test
    void productionAcceptsACompleteConfiguration() {
        PitschProperties p = new PitschProperties();
        p.setMode("production");
        p.getSecurity().setJwtSecret("Zm9vYmFyYmF6cXV4MTIzNDU2Nzg5MGFiY2RlZmdoaWprbG1ub3A");
        p.getSecurity().setEncryptionKeys("k1:MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        p.getCors().setAllowedOrigins(List.of("https://app.pitsch.example"));
        p.setFrontendUrl("https://app.pitsch.example");
        p.setPublicApiUrl("https://api.pitsch.example");
        p.getAi().setInternalToken("an-internal-token-that-is-long-enough-1234");
        p.getStorage().getS3().setBucket("pitsch-files");
        List<String> problems = ProductionConfigValidator.validate(p, "jdbc:postgresql://db:5432/pitsch", "smtp.example.com", false);
        assertTrue(problems.isEmpty(), String.join("\n", problems));

        p.getCors().setAllowedOrigins(List.of("*"));
        assertFalse(ProductionConfigValidator.validate(p, "jdbc:postgresql://db:5432/pitsch", "smtp.example.com", false).isEmpty());
    }
}
