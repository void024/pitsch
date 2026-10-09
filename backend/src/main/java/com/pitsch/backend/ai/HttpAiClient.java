package com.pitsch.backend.ai;

import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.config.PitschProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * HTTP client for the FastAPI AI service. Authenticates with the shared internal service token, retries only what
 * the AI service marks retryable (or when it can't be reached) with exponential backoff.
 */
@Component
public class HttpAiClient implements AiClient {

    public static final String TOKEN_HEADER = "X-Internal-Token";
    private static final Logger log = LoggerFactory.getLogger(HttpAiClient.class);

    private final RestClient rest;
    private final Json json;
    private final int maxAttempts;
    private final long backoffMillis;

    public HttpAiClient(Json json, PitschProperties props) {
        PitschProperties.Ai cfg = props.getAi();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(cfg.getConnectTimeoutSeconds()));
        factory.setReadTimeout(Duration.ofSeconds(cfg.getReadTimeoutSeconds()));
        RestClient.Builder builder = RestClient.builder().baseUrl(cfg.getBaseUrl()).requestFactory(factory);
        if (cfg.getInternalToken() != null && !cfg.getInternalToken().isBlank()) {
            builder.defaultHeader(TOKEN_HEADER, cfg.getInternalToken());
        }
        this.rest = builder.build();
        this.maxAttempts = Math.max(1, cfg.getMaxAttempts());
        this.backoffMillis = cfg.getRetryBackoffMillis();
        this.json = json;
    }



    @Override
    public AgentResult call(Agent agent, String executionId, String traceId, JsonNode input) {
        ObjectNode request = json.obj();
        request.put("executionId", executionId);
        request.put("traceId", traceId);
        request.set("input", input);

        AgentResult result = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            result = once(agent, request);
            if (result.success() || !result.retryable() || attempt == maxAttempts) {
                break;
            }
            log.warn("Agent {} failed ({}), retrying (attempt {}/{})", agent, result.errorCode(), attempt + 1, maxAttempts);
            sleep(backoffMillis * (1L << (attempt - 1)));
        }
        return result;
    }

    private AgentResult once(Agent agent, ObjectNode request) {
        try {
            String requestId = com.pitsch.backend.common.RequestContext.requestId();
            JsonNode body = rest.post()
                    .uri("/agents/" + agent.path())
                    .header("X-Request-Id", requestId == null ? java.util.UUID.randomUUID().toString() : requestId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
            return parse(agent, body);
        } catch (RestClientResponseException e) {
            // 422 = the backend sent input the agent rejected; the body is still the common envelope.
            JsonNode body = json.readSafely(e.getResponseBodyAsString());
            if (body != null && body.has("error")) {
                return parse(agent, body);
            }
            if (e.getStatusCode().value() == 401) {
                log.error("AI service rejected the internal token (check AI_SERVICE_TOKEN on both services)");
            }
            return AgentResult.failure(agent.name(), "AI_SERVICE_HTTP_" + e.getStatusCode().value(),
                    "AI service returned HTTP " + e.getStatusCode().value(), e.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException e) {
            return AgentResult.failure(agent.name(), "AI_SERVICE_UNAVAILABLE", "The AI service could not be reached", true);
        } catch (RestClientException | IllegalStateException e) {
            log.error("Unexpected AI client error for {}", agent, e);
            return AgentResult.failure(agent.name(), "AI_CLIENT_ERROR", "Unexpected error calling the AI service", false);
        }
    }

    /** Parses the AI service's common envelope. Package-private static so it can be tested directly. */
    static AgentResult parse(Agent agent, JsonNode body) {
        if (body == null) {
            return AgentResult.failure(agent.name(), "AI_EMPTY_RESPONSE", "AI service returned an empty response", true);
        }
        boolean success = body.path("success").asBoolean(false);
        JsonNode error = body.path("error");
        String message = Json.text(error, "message");
        JsonNode details = error.get("details");
        if (details != null && details.isArray() && details.size() > 0) {
            message = message + ": " + details.get(0).path("loc") + " " + Json.text(details.get(0), "msg");
        }
        return new AgentResult(success, Json.text(body, "agent") != null ? Json.text(body, "agent") : agent.name(),
                body.get("data"), Json.text(error, "code"), message, error.path("retryable").asBoolean(false),
                body.get("meta"));
    }

    @Override
    public boolean isHealthy() {
        try {
            JsonNode body = rest.get().uri("/health").retrieve().body(JsonNode.class);
            return body != null && "ok".equals(Json.text(body, "status"));
        } catch (RestClientException e) {
            return false;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
