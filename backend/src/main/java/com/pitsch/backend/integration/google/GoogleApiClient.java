package com.pitsch.backend.integration.google;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.config.PitschProperties;
import com.pitsch.backend.integration.IntegrationConnection;
import com.pitsch.backend.integration.IntegrationConnectionRepository;
import com.pitsch.backend.integration.IntegrationException;
import com.pitsch.backend.observability.PitschMetrics;
import com.pitsch.backend.security.TokenCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.DefaultUriBuilderFactory;

/**
 * Authorized calls to Google REST APIs on behalf of one connection: transparent access-token refresh, one retry
 * after a 401, bounded exponential backoff for 429/5xx/timeouts, error mapping and metrics. Tokens are decrypted
 * only in memory for the duration of a call and never logged.
 */
@Component
public class GoogleApiClient {

    private static final Logger log = LoggerFactory.getLogger(GoogleApiClient.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration EXPIRY_SKEW = Duration.ofSeconds(60);

    private final RestClient rest;
    private final GoogleOAuthClient oauth;
    private final TokenCipher cipher;
    private final IntegrationConnectionRepository connections;
    private final TransactionTemplate tx;
    private final PitschMetrics metrics;
    private final Json json;
    private final Clock clock;
    private final Map<Long, Object> refreshLocks = new ConcurrentHashMap<>();

    public GoogleApiClient(GoogleOAuthClient oauth, TokenCipher cipher, IntegrationConnectionRepository connections,
                           PlatformTransactionManager txManager, PitschMetrics metrics, Json json, Clock clock,
                           PitschProperties props) {
        this.oauth = oauth;
        this.cipher = cipher;
        this.connections = connections;
        this.metrics = metrics;
        this.json = json;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(props.getGoogle().getApiTimeoutSeconds()));
        DefaultUriBuilderFactory uris = new DefaultUriBuilderFactory();
        uris.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.TEMPLATE_AND_VALUES);
        this.rest = RestClient.builder().requestFactory(factory).uriBuilderFactory(uris).build();
    }

    public JsonNode get(IntegrationConnection c, String op, String uriTemplate, Object... vars) {
        return call(c, op, HttpMethod.GET, uriTemplate, null, vars);
    }

    public JsonNode post(IntegrationConnection c, String op, String uriTemplate, Object body, Object... vars) {
        return call(c, op, HttpMethod.POST, uriTemplate, body, vars);
    }

    public JsonNode put(IntegrationConnection c, String op, String uriTemplate, Object body, Object... vars) {
        return call(c, op, HttpMethod.PUT, uriTemplate, body, vars);
    }

    public JsonNode patch(IntegrationConnection c, String op, String uriTemplate, Object body, Object... vars) {
        return call(c, op, HttpMethod.PATCH, uriTemplate, body, vars);
    }

    public JsonNode delete(IntegrationConnection c, String op, String uriTemplate, Object... vars) {
        return call(c, op, HttpMethod.DELETE, uriTemplate, null, vars);
    }

    private JsonNode call(IntegrationConnection connection, String op, HttpMethod method, String uriTemplate,
                          Object body, Object... vars) {
        boolean refreshedAfter401 = false;
        for (int attempt = 1; ; attempt++) {
            String token = accessToken(connection, false);
            try {
                RestClient.RequestBodySpec spec = rest.method(method).uri(uriTemplate, vars)
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON);
                if (body != null) {
                    spec.contentType(MediaType.APPLICATION_JSON).body(body);
                }
                JsonNode result = spec.retrieve().body(JsonNode.class);
                metrics.integrationCall("google", op, true);
                return result;
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                if (status == 401 && !refreshedAfter401) {
                    refreshedAfter401 = true;
                    accessToken(connection, true);
                    continue;
                }
                boolean transientError = status == 429 || status >= 500;
                if (transientError && attempt < MAX_ATTEMPTS) {
                    sleep(attempt);
                    continue;
                }
                metrics.integrationCall("google", op, false);
                throw mapError(op, status, e.getResponseBodyAsString());
            } catch (ResourceAccessException e) {
                if (attempt < MAX_ATTEMPTS) {
                    sleep(attempt);
                    continue;
                }
                metrics.integrationCall("google", op, false);
                throw new IntegrationException("Google did not respond (" + op + ")", true, false, 0);
            }
        }
    }

    /** Current access token, refreshing (and persisting, encrypted) when it is about to expire or {@code force}. */
    private String accessToken(IntegrationConnection connection, boolean force) {
        Instant now = clock.instant();
        if (!force && connection.getAccessTokenEnc() != null && connection.getAccessTokenExpiresAt() != null
                && connection.getAccessTokenExpiresAt().isAfter(now.plus(EXPIRY_SKEW))) {
            return cipher.decrypt(connection.getAccessTokenEnc());
        }
        synchronized (refreshLocks.computeIfAbsent(connection.getId(), k -> new Object())) {
            IntegrationConnection fresh = connections.findById(connection.getId())
                    .orElseThrow(() -> new IntegrationException("Integration was disconnected", false, true, 0));
            if (!force && fresh.getAccessTokenEnc() != null && fresh.getAccessTokenExpiresAt() != null
                    && fresh.getAccessTokenExpiresAt().isAfter(now.plus(EXPIRY_SKEW))) {
                copyTokens(fresh, connection);
                return cipher.decrypt(fresh.getAccessTokenEnc());
            }
            if (fresh.getRefreshTokenEnc() == null) {
                markNeedsReconnect(fresh.getId(), "No refresh token stored");
                throw new IntegrationException("Reconnect your Google account.", false, true, 0);
            }
            GoogleOAuthClient.Tokens tokens;
            try {
                tokens = oauth.refresh(cipher.decrypt(fresh.getRefreshTokenEnc()));
            } catch (IntegrationException e) {
                if (e.isNeedsReconnect()) {
                    markNeedsReconnect(fresh.getId(), e.getMessage());
                }
                throw e;
            }
            IntegrationConnection saved = tx.execute(status -> {
                IntegrationConnection c = connections.findById(fresh.getId()).orElseThrow();
                c.setAccessTokenEnc(cipher.encrypt(tokens.accessToken()));
                c.setAccessTokenExpiresAt(clock.instant().plusSeconds(tokens.expiresInSeconds()));
                if (tokens.refreshToken() != null) {
                    c.setRefreshTokenEnc(cipher.encrypt(tokens.refreshToken()));
                } else if (cipher.needsReencryption(c.getRefreshTokenEnc())) {
                    // Key rotation: move the stored refresh token to the current key while we are here.
                    c.setRefreshTokenEnc(cipher.encrypt(cipher.decrypt(c.getRefreshTokenEnc())));
                }
                return connections.save(c);
            });
            copyTokens(saved, connection);
            return tokens.accessToken();
        }
    }

    private void markNeedsReconnect(Long connectionId, String reason) {
        tx.executeWithoutResult(status -> connections.findById(connectionId).ifPresent(c -> {
            c.setStatus(IntegrationConnection.Status.NEEDS_RECONNECT.name());
            c.setLastError(Json.truncate(reason, 1000));
            connections.save(c);
        }));
        log.warn("Google connection {} needs to be reconnected", connectionId);
    }

    private static void copyTokens(IntegrationConnection from, IntegrationConnection to) {
        to.setAccessTokenEnc(from.getAccessTokenEnc());
        to.setAccessTokenExpiresAt(from.getAccessTokenExpiresAt());
        to.setRefreshTokenEnc(from.getRefreshTokenEnc());
    }

    private IntegrationException mapError(String op, int status, String responseBody) {
        JsonNode body = json.readSafely(responseBody);
        String reason = Json.text(body == null ? null : body.path("error"), "message");
        String message = switch (status) {
            case 403 -> "Google refused " + op + (reason == null ? "" : ": " + reason) + ". Reconnect to grant access.";
            case 404 -> "Google could not find the requested " + op + " resource.";
            case 409 -> "Google reported a conflict for " + op + ".";
            case 429 -> "Google rate limit reached for " + op + ". Pitsch will retry.";
            default -> "Google " + op + " failed (HTTP " + status + ").";
        };
        return new IntegrationException(message, status == 429 || status >= 500, false, status);
    }

    private static void sleep(int attempt) {
        try {
            Thread.sleep(500L * (1L << (attempt - 1)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
