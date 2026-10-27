package com.pitsch.backend.workflow;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * One workflow per incoming email. Agent outputs are stored verbatim as JSON so later agents receive
 * exactly what earlier agents produced (the AI service contract expects that).
 */
@Entity
@Table(name = "workflows")
public class Workflow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long organizationId;

    /** Owner: the member who imported the email or whose mailbox received it. */
    private Long userId;
    private Long emailId;
    private Long pitchId;

    /** NEW_PITCH | FOLLOW_UP | NOT_PITCH (null while classifying). */
    private String type;
    private String status;
    private String currentStep;
    private String recommendedAction;

    @Column(length = 500)
    private String availableActions;

    /** One-line description shown in lists, e.g. the email subject. */
    @Column(length = 1000)
    private String summary;

    @Column(columnDefinition = "TEXT") private String classificationJson;
    @Column(columnDefinition = "TEXT") private String documentJson;
    @Column(columnDefinition = "TEXT") private String researchJson;
    @Column(columnDefinition = "TEXT") private String verificationJson;
    @Column(columnDefinition = "TEXT") private String analysisJson;
    @Column(columnDefinition = "TEXT") private String briefMarkdown;
    @Column(columnDefinition = "TEXT") private String calendarJson;

    private Instant meetingStart;
    private Instant meetingEnd;
    private Long meetingEventId;

    private boolean needsHumanReview;
    @Column(columnDefinition = "TEXT") private String reviewReasonsJson;
    @Column(columnDefinition = "TEXT") private String warningsJson;

    /** Action Agent action IDs already executed (idempotency). */
    @Column(columnDefinition = "TEXT") private String executedActionIds;

    @Column(length = 2000)
    private String error;

    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;
    private Instant lastTransitionAt;

    /** Optimistic locking: concurrent jobs/requests cannot silently overwrite each other's state changes. */
    @Version
    private long version;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long v) { this.organizationId = v; }
    public Instant getLastTransitionAt() { return lastTransitionAt; }
    public void setLastTransitionAt(Instant v) { this.lastTransitionAt = v; }
    public long getVersion() { return version; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getEmailId() { return emailId; }
    public void setEmailId(Long emailId) { this.emailId = emailId; }
    public Long getPitchId() { return pitchId; }
    public void setPitchId(Long pitchId) { this.pitchId = pitchId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCurrentStep() { return currentStep; }
    public void setCurrentStep(String currentStep) { this.currentStep = currentStep; }
    public String getRecommendedAction() { return recommendedAction; }
    public void setRecommendedAction(String recommendedAction) { this.recommendedAction = recommendedAction; }
    public String getAvailableActions() { return availableActions; }
    public void setAvailableActions(String availableActions) { this.availableActions = availableActions; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getClassificationJson() { return classificationJson; }
    public void setClassificationJson(String v) { this.classificationJson = v; }
    public String getDocumentJson() { return documentJson; }
    public void setDocumentJson(String v) { this.documentJson = v; }
    public String getResearchJson() { return researchJson; }
    public void setResearchJson(String v) { this.researchJson = v; }
    public String getVerificationJson() { return verificationJson; }
    public void setVerificationJson(String v) { this.verificationJson = v; }
    public String getAnalysisJson() { return analysisJson; }
    public void setAnalysisJson(String v) { this.analysisJson = v; }
    public String getBriefMarkdown() { return briefMarkdown; }
    public void setBriefMarkdown(String v) { this.briefMarkdown = v; }
    public String getCalendarJson() { return calendarJson; }
    public void setCalendarJson(String v) { this.calendarJson = v; }
    public Instant getMeetingStart() { return meetingStart; }
    public void setMeetingStart(Instant meetingStart) { this.meetingStart = meetingStart; }
    public Instant getMeetingEnd() { return meetingEnd; }
    public void setMeetingEnd(Instant meetingEnd) { this.meetingEnd = meetingEnd; }
    public Long getMeetingEventId() { return meetingEventId; }
    public void setMeetingEventId(Long meetingEventId) { this.meetingEventId = meetingEventId; }
    public boolean isNeedsHumanReview() { return needsHumanReview; }
    public void setNeedsHumanReview(boolean needsHumanReview) { this.needsHumanReview = needsHumanReview; }
    public String getReviewReasonsJson() { return reviewReasonsJson; }
    public void setReviewReasonsJson(String v) { this.reviewReasonsJson = v; }
    public String getWarningsJson() { return warningsJson; }
    public void setWarningsJson(String v) { this.warningsJson = v; }
    public String getExecutedActionIds() { return executedActionIds; }
    public void setExecutedActionIds(String v) { this.executedActionIds = v; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
