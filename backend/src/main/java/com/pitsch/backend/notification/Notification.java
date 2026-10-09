package com.pitsch.backend.notification;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long organizationId;

    private Long userId;

    private String type;
    private String title;

    @Column(length = 1000)
    private String message;

    private Long workflowId;

    @Column(name = "is_read")
    private boolean read;

    private Instant readAt;

    /** Unique per user: the same event never produces two notifications. */
    @Column(length = 200)
    private String dedupeKey;

    /** SKIPPED | PENDING | SENT | FAILED */
    @Column(nullable = false, length = 20)
    private String emailStatus = "SKIPPED";

    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long v) { this.organizationId = v; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Long getWorkflowId() { return workflowId; }
    public void setWorkflowId(Long workflowId) { this.workflowId = workflowId; }
    public boolean isRead() { return read; }
    public void setRead(boolean read) { this.read = read; }
    public Instant getReadAt() { return readAt; }
    public void setReadAt(Instant v) { this.readAt = v; }
    public String getDedupeKey() { return dedupeKey; }
    public void setDedupeKey(String v) { this.dedupeKey = v; }
    public String getEmailStatus() { return emailStatus; }
    public void setEmailStatus(String v) { this.emailStatus = v; }
    public Instant getCreatedAt() { return createdAt; }
}
