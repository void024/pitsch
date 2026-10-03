package com.pitsch.backend.workflow;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "email_drafts")
public class EmailDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    private Long workflowId;
    private Long pitchId;
    private String recipient;
    private String recipientName;

    @Column(length = 1000)
    private String subject;

    @Column(columnDefinition = "TEXT")
    private String body;

    private String purpose;

    /** DRAFT | SENT | CANCELLED */
    private String status = "DRAFT";

    private boolean needsHumanReview;
    @Column(columnDefinition = "TEXT") private String reviewReasonsJson;

    private Instant createdAt;
    private Instant sentAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getWorkflowId() { return workflowId; }
    public void setWorkflowId(Long workflowId) { this.workflowId = workflowId; }
    public Long getPitchId() { return pitchId; }
    public void setPitchId(Long pitchId) { this.pitchId = pitchId; }
    public String getRecipient() { return recipient; }
    public void setRecipient(String recipient) { this.recipient = recipient; }
    public String getRecipientName() { return recipientName; }
    public void setRecipientName(String recipientName) { this.recipientName = recipientName; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public boolean isNeedsHumanReview() { return needsHumanReview; }
    public void setNeedsHumanReview(boolean v) { this.needsHumanReview = v; }
    public String getReviewReasonsJson() { return reviewReasonsJson; }
    public void setReviewReasonsJson(String v) { this.reviewReasonsJson = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }
}
