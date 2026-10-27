package com.pitsch.backend.workflow;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pitsch.backend.ai.Agent;
import com.pitsch.backend.ai.AgentResult;
import com.pitsch.backend.ai.AiClient;
import com.pitsch.backend.audit.AuditAction;
import com.pitsch.backend.audit.AuditService;
import com.pitsch.backend.billing.UsageMetric;
import com.pitsch.backend.billing.UsageService;
import com.pitsch.backend.common.Hashing;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.observability.PitschMetrics;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Calls an agent and records the call in agent_executions: model, attempts, tokens, estimated cost, latency, input
 * hash, error category. Updates usage metering and metrics. File contents are redacted from stored inputs.
 */
@Component
public class AgentRunner {

    private final AiClient ai;
    private final AgentExecutionRepository executions;
    private final UsageService usage;
    private final AuditService audit;
    private final PitschMetrics metrics;
    private final TransactionTemplate tx;
    private final Json json;

    public AgentRunner(AiClient ai, AgentExecutionRepository executions, UsageService usage, AuditService audit,
                       PitschMetrics metrics, PlatformTransactionManager txManager, Json json) {
        this.ai = ai;
        this.executions = executions;
        this.usage = usage;
        this.audit = audit;
        this.metrics = metrics;
        this.json = json;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public AgentResult run(Long orgId, Long workflowId, Agent agent, JsonNode input) {
        String inputJson = json.write(input);
        AgentExecution started = tx.execute(s -> {
            int step = (int) executions.countByWorkflowId(workflowId) + 1;
            AgentExecution ex = new AgentExecution();
            ex.setOrganizationId(orgId);
            ex.setWorkflowId(workflowId);
            ex.setAgentName(agent.name());
            ex.setStep(step);
            ex.setExecutionId(workflowId + ":" + agent.name() + ":" + step + ":" + Hashing.randomToken().substring(0, 8));
            ex.setStatus("RUNNING");
            ex.setStartedAt(Instant.now());
            ex.setInputHash(Hashing.sha256Hex(inputJson));
            ex.setInputJson(json.write(redact(input.deepCopy())));
            return executions.save(ex);
        });

        AgentResult result = ai.call(agent, started.getExecutionId(), "workflow-" + workflowId, input);

        tx.executeWithoutResult(s -> {
            AgentExecution ex = executions.findById(started.getId()).orElseThrow();
            ex.setStatus(result.success() ? "COMPLETED" : "FAILED");
            ex.setErrorCode(result.errorCode());
            ex.setErrorCategory(result.errorCategory());
            ex.setErrorMessage(Json.truncate(result.errorMessage(), 2000));
            ex.setRetryable(result.retryable());
            ex.setPromptTokens(result.promptTokens());
            ex.setCompletionTokens(result.completionTokens());
            ex.setLatencyMs(result.latencyMs());
            ex.setModel(result.model());
            ex.setAttempts(result.attempts());
            ex.setRetryCount(Math.max(0, result.attempts() - 1));
            ex.setEstimatedCostUsd(result.estimatedCostUsd());
            ex.setOutputJson(result.data() == null ? null : json.write(result.data()));
            ex.setCompletedAt(Instant.now());
            executions.save(ex);
        });

        long tokens = result.promptTokens() + result.completionTokens();
        usage.record(orgId, null, UsageMetric.AI_TOKENS, tokens, workflowId);
        metrics.agentCall(agent.name(), result.success() ? "completed" : "failed", result.latencyMs(),
                result.promptTokens(), result.completionTokens(), result.estimatedCostUsd());
        if (agent != Agent.ACTION_AGENT) {
            audit.recordAi(orgId, result.success() ? AuditAction.AGENT_COMPLETED : AuditAction.AGENT_FAILED, "WORKFLOW",
                    workflowId, Map.of("agent", agent.name(), "tokens", tokens,
                            "errorCode", result.errorCode() == null ? "" : result.errorCode()));
        }
        return result;
    }

    /** Replace base64 file contents so the execution log doesn't store whole pitch decks. */
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
