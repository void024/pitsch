package com.pitsch.backend.pitch;

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

/** A startup being tracked (one row in the deal pipeline). Never serialised directly — see PitchView. */
@Entity
@Table(name = "pitches")
public class Pitch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long organizationId;

    /** Creator (legacy owner column). */
    private Long userId;

    private Long ownerUserId;

    private String companyName;
    private String founderName;
    private String founderEmail;
    private String companyDomain;
    private String website;
    private String sector;
    /** Funding stage stated by the founder (e.g. Seed). */
    private String stage;

    @Column(length = 1000)
    private String oneLiner;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String amountRequested;

    /** EMAIL | MANUAL */
    private String source = "MANUAL";

    /** Processing status derived from workflows: NEW, FOLLOW_UP, PROCESSING, AWAITING_REVIEW, MEETING_SCHEDULED, COMPLETED, STOPPED. */
    private String status = "NEW";

    /** Human-owned deal stage. */
    @Column(nullable = false, length = 40)
    private String dealStage = DealStage.NEW.name();

    /** Classifier confidence of the latest classification (0–1). */
    private Double aiConfidence;

    private Integer claimsSupported;
    private Integer claimsContradicted;
    private Integer claimsUnresolved;

    /** Evidence risk (LOW / MEDIUM / HIGH) from the claims summary — an evidence signal, not investment advice. */
    @Column(length = 10)
    private String riskLevel;

    @Column(nullable = false)
    private boolean hasFollowUp;

    private Long firstEmailId;

    @Column(length = 2000)
    private String threadIds;

    private Long latestWorkflowId;
    private Long latestBriefWorkflowId;
    private Instant lastActivityAt;
    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private long version;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
        if (lastActivityAt == null) {
            lastActivityAt = createdAt;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long v) { this.organizationId = v; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long v) { this.ownerUserId = v; }
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
    public String getDealStage() { return dealStage; }
    public void setDealStage(String v) { this.dealStage = v; }
    public Double getAiConfidence() { return aiConfidence; }
    public void setAiConfidence(Double v) { this.aiConfidence = v; }
    public Integer getClaimsSupported() { return claimsSupported; }
    public void setClaimsSupported(Integer v) { this.claimsSupported = v; }
    public Integer getClaimsContradicted() { return claimsContradicted; }
    public void setClaimsContradicted(Integer v) { this.claimsContradicted = v; }
    public Integer getClaimsUnresolved() { return claimsUnresolved; }
    public void setClaimsUnresolved(Integer v) { this.claimsUnresolved = v; }
    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String v) { this.riskLevel = v; }
    public boolean isHasFollowUp() { return hasFollowUp; }
    public void setHasFollowUp(boolean v) { this.hasFollowUp = v; }
    public Long getFirstEmailId() { return firstEmailId; }
    public void setFirstEmailId(Long firstEmailId) { this.firstEmailId = firstEmailId; }
    public String getThreadIds() { return threadIds; }
    public void setThreadIds(String threadIds) { this.threadIds = threadIds; }
    public Long getLatestWorkflowId() { return latestWorkflowId; }
    public void setLatestWorkflowId(Long latestWorkflowId) { this.latestWorkflowId = latestWorkflowId; }
    public Long getLatestBriefWorkflowId() { return latestBriefWorkflowId; }
    public void setLatestBriefWorkflowId(Long v) { this.latestBriefWorkflowId = v; }
    public Instant getLastActivityAt() { return lastActivityAt; }
    public void setLastActivityAt(Instant v) { this.lastActivityAt = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
