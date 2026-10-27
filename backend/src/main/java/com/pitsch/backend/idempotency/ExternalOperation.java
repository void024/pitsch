package com.pitsch.backend.idempotency;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Ledger row for one external side effect (send, create event, sheet write), keyed by a backend-owned key. */
@Entity
@Table(name = "external_operations")
public class ExternalOperation {

    public enum Status { PENDING, SUCCEEDED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long organizationId;

    @Column(nullable = false, unique = true, length = 200)
    private String idempotencyKey;

    @Column(nullable = false, length = 40)
    private String operationType;

    @Column(nullable = false, length = 20)
    private String status;

    private String externalId;
    private Long workflowId;

    @Column(nullable = false)
    private int attempts;

    @Column(length = 1000)
    private String error;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long v) { this.organizationId = v; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String v) { this.idempotencyKey = v; }
    public String getOperationType() { return operationType; }
    public void setOperationType(String v) { this.operationType = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String v) { this.externalId = v; }
    public Long getWorkflowId() { return workflowId; }
    public void setWorkflowId(Long v) { this.workflowId = v; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int v) { this.attempts = v; }
    public String getError() { return error; }
    public void setError(String v) { this.error = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
