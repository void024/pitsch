package com.pitsch.backend.approval;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * AI recommendation → human approval → execution → audit. An external action that needs approval can only run with
 * an APPROVED row created by a user request; the AI service can propose but never approve.
 */
@Entity
@Table(name = "approvals")
public class Approval {

    public enum Status { PENDING, APPROVED, REJECTED, EXECUTING, EXECUTED, FAILED, SUPERSEDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long organizationId;

    private Long workflowId;
    private Long pitchId;

    @Column(nullable = false, length = 40)
    private String actionType;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false, length = 500)
    private String summary;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    /** SHA-256 of payloadJson at decision time: what was approved is exactly what is executed. */
    @Column(nullable = false, length = 64)
    private String payloadHash;

    /** AI | USER | SYSTEM */
    @Column(nullable = false, length = 20)
    private String proposedBy;

    @Column(length = 1000)
    private String reason;

    private Long decidedByUserId;
    private Instant decidedAt;
    private Instant executedAt;

    @Column(nullable = false, unique = true, length = 200)
    private String idempotencyKey;

    @Column(length = 1000)
    private String error;

    @Column(nullable = false)
    private Instant createdAt;

    @Version
    private long version;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public ApprovalType type() {
        return ApprovalType.valueOf(actionType);
    }

    public Long getId() { return id; }
    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long v) { this.organizationId = v; }
    public Long getWorkflowId() { return workflowId; }
    public void setWorkflowId(Long v) { this.workflowId = v; }
    public Long getPitchId() { return pitchId; }
    public void setPitchId(Long v) { this.pitchId = v; }
    public String getActionType() { return actionType; }
    public void setActionType(String v) { this.actionType = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getSummary() { return summary; }
    public void setSummary(String v) { this.summary = v; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String v) { this.payloadJson = v; }
    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String v) { this.payloadHash = v; }
    public String getProposedBy() { return proposedBy; }
    public void setProposedBy(String v) { this.proposedBy = v; }
    public String getReason() { return reason; }
    public void setReason(String v) { this.reason = v; }
    public Long getDecidedByUserId() { return decidedByUserId; }
    public void setDecidedByUserId(Long v) { this.decidedByUserId = v; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant v) { this.decidedAt = v; }
    public Instant getExecutedAt() { return executedAt; }
    public void setExecutedAt(Instant v) { this.executedAt = v; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String v) { this.idempotencyKey = v; }
    public String getError() { return error; }
    public void setError(String v) { this.error = v; }
    public Instant getCreatedAt() { return createdAt; }
    public long getVersion() { return version; }
}
