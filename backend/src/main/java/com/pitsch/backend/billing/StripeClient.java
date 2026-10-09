package com.pitsch.backend.billing;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.ErrorCode;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.config.PitschProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Minimal Stripe REST client (form-encoded requests, no SDK): Checkout Sessions, Billing Portal sessions and webhook
 * signature verification. Card data never touches Pitsch — Stripe-hosted pages collect it.
 */
@Component
public class StripeClient {

    private static final Logger log = LoggerFactory.getLogger(StripeClient.class);
    private static final String API = "https://api.stripe.com/v1/";
    static final long SIGNATURE_TOLERANCE_SECONDS = 300;

    private final PitschProperties props;
    private final Json json;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public StripeClient(PitschProperties props, Json json, Clock clock) {
        this.props = props;
        this.json = json;
        this.clock = clock;
    }

    public boolean configured() {
        return props.getBilling().isStripe() && !blank(props.getBilling().getStripeSecretKey());
    }

    public JsonNode post(String path, Map<String, String> form, String idempotencyKey) {
        if (!configured()) {
            throw new ApiException(ErrorCode.INTEGRATION_NOT_CONFIGURED, "Online billing is not enabled on this deployment.");
        }
        String body = form.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(API + path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + props.getBilling().getStripeSecretKey())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (idempotencyKey != null) {
            req.header("Idempotency-Key", idempotencyKey);
        }
        try {
            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode node = json.read(res.body());
            if (res.statusCode() >= 300) {
                String message = node == null ? null : node.path("error").path("message").asText(null);
                log.warn("Stripe {} failed with HTTP {}", path, res.statusCode());
                throw new ApiException(ErrorCode.INTEGRATION_ERROR, "The billing provider rejected the request"
                        + (message == null ? "." : ": " + message));
            }
            return node;
        } catch (java.io.IOException e) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "The billing provider is unreachable. Try again shortly.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, "The billing request was interrupted.");
        }
    }

    /**
     * Verifies a {@code Stripe-Signature} header ("t=...,v1=...") against the raw payload: HMAC-SHA256 of
     * "{t}.{payload}" with the endpoint secret, constant-time compared, and a 5-minute replay window.
     */
    public boolean verifySignature(String payload, String header) {
        String secret = props.getBilling().getStripeWebhookSecret();
        if (blank(secret) || header == null || payload == null) {
            return false;
        }
        Map<String, java.util.List<String>> parts = new LinkedHashMap<>();
        for (String item : header.split(",")) {
            int i = item.indexOf('=');
            if (i > 0) {
                parts.computeIfAbsent(item.substring(0, i).trim(), k -> new java.util.ArrayList<>()).add(item.substring(i + 1).trim());
            }
        }
        if (!parts.containsKey("t") || !parts.containsKey("v1")) {
            return false;
        }
        long t;
        try {
            t = Long.parseLong(parts.get("t").get(0));
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(clock.instant().getEpochSecond() - t) > SIGNATURE_TOLERANCE_SECONDS) {
            return false;
        }
        byte[] expected = hmac(secret, t + "." + payload);
        for (String v1 : parts.get("v1")) {
            try {
                if (MessageDigest.isEqual(expected, HexFormat.of().parseHex(v1))) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // malformed signature value
            }
        }
        return false;
    }

    static byte[] hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
