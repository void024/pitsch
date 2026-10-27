package com.pitsch.backend.email;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** An incoming email (untrusted content). One workflow is started per email. */
@Entity
@Table(name = "emails")
public class Email {

    public enum Source { MANUAL, GMAIL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long organizationId;

    /** The member who imported it, or whose Gmail it arrived in (workflow owner). */
    private Long userId;

    @Column(nullable = false, length = 20)
    private String source = Source.MANUAL.name();

    /** "gmail:&lt;id&gt;" or "msgid:&lt;Message-ID&gt;"; unique per workspace — the same email is never processed twice. */
    @Column(length = 300)
    private String dedupeKey;

    /** Gmail connection it was ingested through (labels are applied to that mailbox). */
    private Long connectionId;

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

    /** Pitsch labels (also mirrored to Gmail when the email came from Gmail and policy allows). */
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
    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long v) { this.organizationId = v; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public String getDedupeKey() { return dedupeKey; }
    public void setDedupeKey(String v) { this.dedupeKey = v; }
    public Long getConnectionId() { return connectionId; }
    public void setConnectionId(Long v) { this.connectionId = v; }
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
