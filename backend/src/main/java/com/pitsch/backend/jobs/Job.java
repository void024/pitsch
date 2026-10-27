package com.pitsch.backend.jobs;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A unit of background work persisted in PostgreSQL (survives restarts and deploys). */
@Entity
@Table(name = "jobs")
public class Job {

    public enum Status { QUEUED, RUNNING, SUCCEEDED, DEAD, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long organizationId;
    private Long workflowId;

    @Column(nullable = false, length = 60)
    private String type;

    @Column(columnDefinition = "TEXT")
    private String payloadJson;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private int priority;

    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false)
    private int maxAttempts;

    @Column(nullable = false)
    private Instant runAt;

    @Column(length = 100)
    private String lockedBy;

    private Instant lockedAt;

    @Column(length = 2000)
    private String lastError;

    /** Unique: the same logical work (e.g. one Gmail message) can only be enqueued once. */
    @Column(length = 200, unique = true)
    private String dedupeKey;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant completedAt;

    public Long getId() { return id; }
    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long v) { this.organizationId = v; }
    public Long getWorkflowId() { return workflowId; }
    public void setWorkflowId(Long v) { this.workflowId = v; }
    public String getType() { return type; }
    public void setType(String v) { this.type = v; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String v) { this.payloadJson = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public int getPriority() { return priority; }
    public void setPriority(int v) { this.priority = v; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int v) { this.attempts = v; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int v) { this.maxAttempts = v; }
    public Instant getRunAt() { return runAt; }
    public void setRunAt(Instant v) { this.runAt = v; }
    public String getLockedBy() { return lockedBy; }
    public void setLockedBy(String v) { this.lockedBy = v; }
    public Instant getLockedAt() { return lockedAt; }
    public void setLockedAt(Instant v) { this.lockedAt = v; }
    public String getLastError() { return lastError; }
    public void setLastError(String v) { this.lastError = v; }
    public String getDedupeKey() { return dedupeKey; }
    public void setDedupeKey(String v) { this.dedupeKey = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant v) { this.completedAt = v; }
}
