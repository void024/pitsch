package com.pitsch.backend.ai;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The AI service's common envelope: { success, agent, data, error{code,message,retryable}, meta }.
 * Transport problems (AI service down, timeout) are reported the same way with code AI_SERVICE_UNAVAILABLE.
 */
public record AgentResult(boolean success, String agent, JsonNode data, String errorCode, String errorMessage,
                          boolean retryable, JsonNode meta) {

    public static AgentResult failure(String agent, String code, String message, boolean retryable) {
        return new AgentResult(false, agent, null, code, message, retryable, null);
    }

    public long promptTokens() {
        return meta == null ? 0 : meta.path("promptTokens").asLong(0);
    }

    public long completionTokens() {
        return meta == null ? 0 : meta.path("completionTokens").asLong(0);
    }

    public long latencyMs() {
        return meta == null ? 0 : meta.path("latencyMs").asLong(0);
    }
}
