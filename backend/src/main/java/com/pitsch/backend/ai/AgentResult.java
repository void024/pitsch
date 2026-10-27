package com.pitsch.backend.ai;

import java.math.BigDecimal;

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

    public int attempts() {
        return meta == null ? 0 : meta.path("attempts").asInt(0);
    }

    public String model() {
        return meta == null || meta.path("model").isNull() ? null : meta.path("model").asText(null);
    }

    public BigDecimal estimatedCostUsd() {
        if (meta == null || !meta.has("estimatedCostUsd") || meta.path("estimatedCostUsd").isNull()) {
            return null;
        }
        return meta.path("estimatedCostUsd").decimalValue();
    }

    /** PROVIDER | TIMEOUT | VALIDATION | INPUT | INTERNAL | TRANSPORT */
    public String errorCategory() {
        if (success || errorCode == null) {
            return null;
        }
        return switch (errorCode) {
            case "LLM_TIMEOUT" -> "TIMEOUT";
            case "LLM_API_ERROR", "SEARCH_FAILED" -> "PROVIDER";
            case "MALFORMED_LLM_OUTPUT" -> "VALIDATION";
            case "INVALID_INPUT", "INSUFFICIENT_INPUT", "DOCUMENT_UNREADABLE" -> "INPUT";
            case "AI_SERVICE_UNAVAILABLE", "AI_EMPTY_RESPONSE" -> "TRANSPORT";
            default -> errorCode.startsWith("AI_SERVICE_HTTP_") ? "TRANSPORT" : "INTERNAL";
        };
    }
}
