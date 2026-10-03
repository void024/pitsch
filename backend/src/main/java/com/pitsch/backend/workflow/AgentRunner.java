package com.pitsch.backend.workflow;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pitsch.backend.ai.Agent;
import com.pitsch.backend.ai.AgentResult;
import com.pitsch.backend.ai.AiClient;
import com.pitsch.backend.common.Json;
import org.springframework.stereotype.Component;

/** Calls an agent and records the call in agent_executions (status, tokens, latency, errors). */
@Component
public class AgentRunner {

    private final AiClient ai;
    private final AgentExecutionRepository executions;
    private final Json json;

    public AgentRunner(AiClient ai, AgentExecutionRepository executions, Json json) {
        this.ai = ai;
        this.executions = executions;
        this.json = json;
    }

    public AgentResult run(Long workflowId, Agent agent, JsonNode input) {
        int step = (int) executions.countByWorkflowId(workflowId) + 1;
        AgentExecution ex = new AgentExecution();
        ex.setWorkflowId(workflowId);
        ex.setAgentName(agent.name());
        ex.setStep(step);
        ex.setExecutionId(workflowId + ":" + agent.name() + ":" + step);
        ex.setStatus("RUNNING");
        ex.setStartedAt(Instant.now());
        ex.setInputJson(json.write(redact(input.deepCopy())));
        executions.save(ex);

        AgentResult result = ai.call(agent, ex.getExecutionId(), "workflow-" + workflowId, input);

        ex.setStatus(result.success() ? "COMPLETED" : "FAILED");
        ex.setErrorCode(result.errorCode());
        ex.setErrorMessage(Json.truncate(result.errorMessage(), 2000));
        ex.setRetryable(result.retryable());
        ex.setPromptTokens(result.promptTokens());
        ex.setCompletionTokens(result.completionTokens());
        ex.setLatencyMs(result.latencyMs());
        ex.setOutputJson(result.data() == null ? null : json.write(result.data()));
        ex.setCompletedAt(Instant.now());
        executions.save(ex);
        return result;
    }

    /** Replace base64 file contents so the audit log doesn't store whole pitch decks twice. */
    private static JsonNode redact(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            for (Map.Entry<String, JsonNode> e : obj.properties()) {
                if ("contentBase64".equals(e.getKey()) && e.getValue().isTextual()) {
                    e.setValue(obj.textNode("<" + e.getValue().asText().length() + " base64 chars>"));
                } else {
                    redact(e.getValue());
                }
            }
        } else if (node instanceof ArrayNode arr) {
            arr.forEach(AgentRunner::redact);
        }
        return node;
    }
}
