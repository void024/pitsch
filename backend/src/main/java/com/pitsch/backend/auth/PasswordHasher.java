package com.pitsch.backend.auth;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import com.pitsch.backend.config.PitschProperties;
import org.springframework.stereotype.Component;

/**
 * PBKDF2-HMAC-SHA256 (JDK only). Format: {@code pbkdf2$iterations$salt$hash}. The iteration count is configurable
 * (OWASP 2023 recommends 600,000); hashes created with fewer iterations are upgraded at the next successful login.
 */
@Component
public class PasswordHasher {

    /** Stored for accounts without a password (Google sign-in only); never matches anything. */
    public static final String NO_PASSWORD = "!";
    private static final int KEY_BITS = 256;
    private final SecureRandom random = new SecureRandom();
    private final int iterations;

    public PasswordHasher(PitschProperties props) {
        this.iterations = Math.max(100_000, props.getSecurity().getPasswordHashIterations());
    }

    public String hash(String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        byte[] hash = derive(password, salt, iterations);
        Base64.Encoder b64 = Base64.getEncoder();
        return "pbkdf2$" + iterations + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(hash);
    }

    public boolean matches(String password, String stored) {
        if (password == null || stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !"pbkdf2".equals(parts[0])) {
            return false;
        }
        try {
            int storedIterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            return MessageDigest.isEqual(expected, derive(password, salt, storedIterations));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public boolean needsRehash(String stored) {
        String[] parts = stored == null ? new String[0] : stored.split("\\$");
        return parts.length == 4 && "pbkdf2".equals(parts[0]) && !String.valueOf(iterations).equals(parts[1]);
    }

    /** Minimum policy: 10+ characters, not only letters or only digits, not the email itself. */
    public static String policyViolation(String password, String email) {
        if (password == null || password.length() < 10) {
            return "Password must be at least 10 characters.";
        }
        if (password.length() > 200) {
            return "Password must be at most 200 characters.";
        }
        if (password.chars().allMatch(Character::isLetter) || password.chars().allMatch(Character::isDigit)) {
            return "Use a mix of letters and numbers or symbols.";
        }
        if (email != null && password.equalsIgnoreCase(email)) {
            return "Password must not be your email address.";
        }
        return null;
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 not available", e);
        }
    }
}
