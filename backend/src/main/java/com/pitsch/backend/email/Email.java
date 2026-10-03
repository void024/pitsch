package com.pitsch.backend.email;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "emails")
public class Email {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    private Long userId;

    /** Gmail message id when ingested from Gmail; null for emails submitted through the API/UI. */
    private String gmailId;
    private String threadId;
    private String messageId;
    private String inReplyTo;

    @Column(nullable = false)
    private String sender;
    private String senderName;

    @Column(length = 2000)
    private String recipients;

    @Column(length = 1000)
    private String subject;

    @Column(columnDefinition = "TEXT")
    private String body;

    private Instant receivedAt;

    private Boolean isPitch;
    private Boolean isFollowUp;
    private Long previousPitchId;
    private String category;

    /** Gmail-style labels applied by the Action Agent (simulated until Gmail is connected). */
    @Column(length = 1000)
    private String labels;

    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        if (receivedAt == null) {
            receivedAt = createdAt;
        }
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getGmailId() { return gmailId; }
    public void setGmailId(String gmailId) { this.gmailId = gmailId; }
    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getInReplyTo() { return inReplyTo; }
    public void setInReplyTo(String inReplyTo) { this.inReplyTo = inReplyTo; }
    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }
    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }
    public String getRecipients() { return recipients; }
    public void setRecipients(String recipients) { this.recipients = recipients; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
    public Boolean getIsPitch() { return isPitch; }
    public void setIsPitch(Boolean isPitch) { this.isPitch = isPitch; }
    public Boolean getIsFollowUp() { return isFollowUp; }
    public void setIsFollowUp(Boolean isFollowUp) { this.isFollowUp = isFollowUp; }
    public Long getPreviousPitchId() { return previousPitchId; }
    public void setPreviousPitchId(Long previousPitchId) { this.previousPitchId = previousPitchId; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getLabels() { return labels; }
    public void setLabels(String labels) { this.labels = labels; }
    public Instant getCreatedAt() { return createdAt; }
}
