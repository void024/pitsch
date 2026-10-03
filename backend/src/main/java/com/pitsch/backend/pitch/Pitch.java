package com.pitsch.backend.pitch;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** A startup being tracked (one row in the deal pipeline). */
@Entity
@Table(name = "pitches")
public class Pitch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    private Long userId;

    /** "title" accepted for compatibility with the original POST /api/pitches payload. */
    @JsonAlias("title")
    private String companyName;

    private String founderName;

    /** "email" accepted for compatibility with the original POST /api/pitches payload. */
    @JsonAlias("email")
    private String founderEmail;

    private String companyDomain;
    private String website;
    private String sector;
    private String stage;

    @Column(length = 1000)
    private String oneLiner;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String amountRequested;

    /** EMAIL | MANUAL */
    private String source = "MANUAL";

    /** Pipeline status, e.g. NEW, PROCESSING, AWAITING_REVIEW, MEETING_SCHEDULED, COMPLETED, STOPPED. */
    private String status = "NEW";

    private Long firstEmailId;

    @JsonIgnore
    @Column(length = 2000)
    private String threadIds;

    private Long latestWorkflowId;
    private Long latestBriefWorkflowId;

    private Instant createdAt;
    private Instant updatedAt;

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
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public String getFounderName() { return founderName; }
    public void setFounderName(String founderName) { this.founderName = founderName; }
    public String getFounderEmail() { return founderEmail; }
    public void setFounderEmail(String founderEmail) { this.founderEmail = founderEmail; }
    public String getCompanyDomain() { return companyDomain; }
    public void setCompanyDomain(String companyDomain) { this.companyDomain = companyDomain; }
    public String getWebsite() { return website; }
    public void setWebsite(String website) { this.website = website; }
    public String getSector() { return sector; }
    public void setSector(String sector) { this.sector = sector; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getOneLiner() { return oneLiner; }
    public void setOneLiner(String oneLiner) { this.oneLiner = oneLiner; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getAmountRequested() { return amountRequested; }
    public void setAmountRequested(String amountRequested) { this.amountRequested = amountRequested; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getFirstEmailId() { return firstEmailId; }
    public void setFirstEmailId(Long firstEmailId) { this.firstEmailId = firstEmailId; }
    public String getThreadIds() { return threadIds; }
    public void setThreadIds(String threadIds) { this.threadIds = threadIds; }
    public Long getLatestWorkflowId() { return latestWorkflowId; }
    public void setLatestWorkflowId(Long latestWorkflowId) { this.latestWorkflowId = latestWorkflowId; }
    public Long getLatestBriefWorkflowId() { return latestBriefWorkflowId; }
    public void setLatestBriefWorkflowId(Long latestBriefWorkflowId) { this.latestBriefWorkflowId = latestBriefWorkflowId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
