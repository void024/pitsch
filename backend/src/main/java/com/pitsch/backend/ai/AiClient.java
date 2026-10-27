package com.pitsch.backend.ai;

import com.fasterxml.jackson.databind.JsonNode;

/** Calls one agent of the Pitsch AI service. Implementations never throw for agent/transport failures. */
public interface AiClient {

    AgentResult call(Agent agent, String executionId, String traceId, JsonNode input);

    /** True if the AI service answers GET /health. */
    boolean isHealthy();
}
