package com.pitsch.backend.workflow;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One call to one agent (audit trail + the per-agent progress shown in the UI). */
@Entity
@Table(name = "agent_executions")
public class AgentExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long workflowId;
    private String agentName;

    /** "<workflowId>:<AGENT>:<step>" — sent to the AI service as executionId. */
    @Column(unique = true)
    private String executionId;

    private int step;

    /** RUNNING | COMPLETED | FAILED */
    private String status;

    private String errorCode;
    @Column(length = 2000) private String errorMessage;
    private boolean retryable;
    private long promptTokens;
    private long completionTokens;
    private long latencyMs;

    /** Input with file contents redacted. */
    @Column(columnDefinition = "TEXT") private String inputJson;
    @Column(columnDefinition = "TEXT") private String outputJson;

    private Instant startedAt;
    private Instant completedAt;

    public Long getId() { return id; }
    public Long getWorkflowId() { return workflowId; }
    public void setWorkflowId(Long workflowId) { this.workflowId = workflowId; }
    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
    public String getExecutionId() { return executionId; }
    public void setExecutionId(String executionId) { this.executionId = executionId; }
    public int getStep() { return step; }
    public void setStep(int step) { this.step = step; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public boolean isRetryable() { return retryable; }
    public void setRetryable(boolean retryable) { this.retryable = retryable; }
    public long getPromptTokens() { return promptTokens; }
    public void setPromptTokens(long v) { this.promptTokens = v; }
    public long getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(long v) { this.completionTokens = v; }
    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long v) { this.latencyMs = v; }
    public String getInputJson() { return inputJson; }
    public void setInputJson(String v) { this.inputJson = v; }
    public String getOutputJson() { return outputJson; }
    public void setOutputJson(String v) { this.outputJson = v; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
