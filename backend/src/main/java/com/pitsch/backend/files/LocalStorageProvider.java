package com.pitsch.backend.files;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.pitsch.backend.common.Hashing;

/**
 * Development/demo/test storage on local disk. Refused in production (ephemeral disks lose data). Download links
 * point at the backend and carry an HMAC signature with an expiry, mimicking S3 presigned URLs.
 */
public class LocalStorageProvider implements StorageProvider {

    private final Path root;
    private final String publicApiUrl;
    private final byte[] signingKey = Hashing.randomToken().getBytes(StandardCharsets.UTF_8);
    private final Clock clock;

    public LocalStorageProvider(String dir, String publicApiUrl, Clock clock) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
        this.publicApiUrl = publicApiUrl;
        this.clock = clock;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create storage directory " + root, e);
        }
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        try {
            Path p = resolve(key);
            Files.createDirectories(p.getParent());
            Files.write(p, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public byte[] get(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public URI signedDownloadUrl(String key, Duration ttl, String downloadFilename, String contentType) {
        long expires = clock.instant().plus(ttl).getEpochSecond();
        String sig = sign(key + "|" + expires);
        return URI.create(publicApiUrl + "/api/v1/files/local?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "&expires=" + expires + "&sig=" + sig + "&name=" + URLEncoder.encode(downloadFilename, StandardCharsets.UTF_8));
    }

    public boolean verify(String key, long expires, String sig) {
        return expires >= clock.instant().getEpochSecond() && Hashing.constantTimeEquals(sign(key + "|" + expires), sig);
    }

    @Override
    public String name() {
        return "local";
    }

    private Path resolve(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalArgumentException("Invalid storage key");
        }
        return p;
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return Hashing.sha256Hex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
