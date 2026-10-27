package com.pitsch.backend.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.pitsch.backend.config.PitschProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM encryption for credentials at rest (OAuth access/refresh tokens, PKCE verifiers).
 * Ciphertext format: {@code v1:<keyId>:<base64(iv || ciphertext+tag)>}. Several keys can be configured
 * ({@code PITSCH_ENCRYPTION_KEYS=new:base64,old:base64}); the first encrypts, all decrypt — so keys can be rotated
 * without downtime. The key ID is bound as additional authenticated data.
 */
@Component
public class TokenCipher {

    private static final Logger log = LoggerFactory.getLogger(TokenCipher.class);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Map<String, SecretKeySpec> keys = new LinkedHashMap<>();
    private final String activeKeyId;
    private final SecureRandom random = new SecureRandom();

    public TokenCipher(PitschProperties props) {
        String configured = props.getSecurity().getEncryptionKeys();
        if (configured == null || configured.isBlank()) {
            if (props.isProduction()) {
                throw new IllegalStateException("PITSCH_ENCRYPTION_KEYS is required in production");
            }
            byte[] ephemeral = new byte[32];
            random.nextBytes(ephemeral);
            keys.put("dev", new SecretKeySpec(ephemeral, "AES"));
            log.warn("PITSCH_ENCRYPTION_KEYS not set: using an ephemeral key (stored integration tokens become unreadable after restart).");
        } else {
            for (String entry : configured.split(",")) {
                String[] parts = entry.trim().split(":", 2);
                if (parts.length != 2 || parts[0].isBlank()) {
                    throw new IllegalStateException("PITSCH_ENCRYPTION_KEYS entries must be keyId:base64key");
                }
                byte[] key = Base64.getDecoder().decode(parts[1].trim());
                if (key.length != 32) {
                    throw new IllegalStateException("Encryption key '" + parts[0] + "' must be 32 bytes (base64 of 32 random bytes)");
                }
                keys.put(parts[0].trim(), new SecretKeySpec(key, "AES"));
            }
        }
        this.activeKeyId = keys.keySet().iterator().next();
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeKeyId), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(activeKeyId.getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
            return "v1:" + activeKeyId + ":" + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.split(":", 3);
        if (parts.length != 3 || !"v1".equals(parts[0])) {
            throw new IllegalStateException("Unrecognised ciphertext format");
        }
        SecretKeySpec key = keys.get(parts[1]);
        if (key == null) {
            throw new IllegalStateException("Unknown encryption key id '" + parts[1] + "'");
        }
        try {
            byte[] all = Base64.getDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            cipher.updateAAD(parts[1].getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }

    /** True if the value was encrypted with an older key and should be re-encrypted. */
    public boolean needsReencryption(String value) {
        return value != null && !value.startsWith("v1:" + activeKeyId + ":");
    }
}
