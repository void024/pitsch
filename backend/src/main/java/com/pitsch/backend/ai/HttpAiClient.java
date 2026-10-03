package com.pitsch.backend.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pitsch.backend.common.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * HTTP client for the FastAPI AI service. Retries only what the AI service marks as retryable
 * (or when the service can't be reached), with exponential backoff.
 */
@Component
public class HttpAiClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(HttpAiClient.class);

    private final RestClient rest;
    private final Json json;
    private final int maxAttempts;
    private final long backoffMillis;
    private final String baseUrl;

    public HttpAiClient(Json json,
                        @Value("${pitsch.ai.base-url}") String baseUrl,
                        @Value("${pitsch.ai.connect-timeout-seconds:5}") int connectTimeoutSeconds,
                        @Value("${pitsch.ai.read-timeout-seconds:240}") int readTimeoutSeconds,
                        @Value("${pitsch.ai.max-attempts:3}") int maxAttempts,
                        @Value("${pitsch.ai.retry-backoff-millis:2000}") long backoffMillis) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutSeconds * 1000);
        factory.setReadTimeout(readTimeoutSeconds * 1000);
        this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.json = json;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.backoffMillis = backoffMillis;
        this.baseUrl = baseUrl;
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
            JsonNode body = rest.post()
                    .uri("/agents/" + agent.path())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(JsonNode.class);
            return parse(agent, body);
        } catch (RestClientResponseException e) {
            // 422 = the backend sent input the agent rejected; the body is still the common envelope.
            JsonNode body = json.read(e.getResponseBodyAsString());
            if (body != null && body.has("error")) {
                return parse(agent, body);
            }
            return AgentResult.failure(agent.name(), "AI_SERVICE_HTTP_" + e.getStatusCode().value(),
                    "AI service returned HTTP " + e.getStatusCode().value(), e.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException e) {
            return AgentResult.failure(agent.name(), "AI_SERVICE_UNAVAILABLE",
                    "Could not reach the AI service at " + baseUrl + " (is it running?)", true);
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
