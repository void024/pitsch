package com.pitsch.backend.org;

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

/** A tenant (shown as "workspace" in the UI). Every tenant-owned row carries organization_id. */
@Entity
@Table(name = "organizations")
public class Organization {

    /** How an AI-proposed external action of a given kind is handled. */
    public enum ActionPolicy { AUTO, APPROVAL, OFF }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, unique = true, length = 80)
    private String slug;

    @Column(nullable = false, length = 64)
    private String timezone = "UTC";

    @Column(nullable = false, length = 5)
    private String workingHoursStart = "09:30";

    @Column(nullable = false, length = 5)
    private String workingHoursEnd = "18:30";

    @Column(nullable = false, length = 40)
    private String workingDays = "MON,TUE,WED,THU,FRI";

    @Column(nullable = false)
    private int meetingDurationMinutes = 30;

    /** Delete email bodies, documents and agent I/O older than this many days; null keeps data until deleted. */
    private Integer dataRetentionDays;

    @Column(nullable = false, length = 20)
    private String gmailLabelPolicy = ActionPolicy.AUTO.name();

    @Column(nullable = false, length = 20)
    private String sheetsSyncPolicy = ActionPolicy.APPROVAL.name();

    private Instant onboardingCompletedAt;
    private Instant deletionRequestedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

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

    public ActionPolicy gmailLabels() { return ActionPolicy.valueOf(gmailLabelPolicy); }
    public ActionPolicy sheetsSync() { return ActionPolicy.valueOf(sheetsSyncPolicy); }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public String getWorkingHoursStart() { return workingHoursStart; }
    public void setWorkingHoursStart(String v) { this.workingHoursStart = v; }
    public String getWorkingHoursEnd() { return workingHoursEnd; }
    public void setWorkingHoursEnd(String v) { this.workingHoursEnd = v; }
    public String getWorkingDays() { return workingDays; }
    public void setWorkingDays(String v) { this.workingDays = v; }
    public int getMeetingDurationMinutes() { return meetingDurationMinutes; }
    public void setMeetingDurationMinutes(int v) { this.meetingDurationMinutes = v; }
    public Integer getDataRetentionDays() { return dataRetentionDays; }
    public void setDataRetentionDays(Integer v) { this.dataRetentionDays = v; }
    public String getGmailLabelPolicy() { return gmailLabelPolicy; }
    public void setGmailLabelPolicy(String v) { this.gmailLabelPolicy = v; }
    public String getSheetsSyncPolicy() { return sheetsSyncPolicy; }
    public void setSheetsSyncPolicy(String v) { this.sheetsSyncPolicy = v; }
    public Instant getOnboardingCompletedAt() { return onboardingCompletedAt; }
    public void setOnboardingCompletedAt(Instant v) { this.onboardingCompletedAt = v; }
    public Instant getDeletionRequestedAt() { return deletionRequestedAt; }
    public void setDeletionRequestedAt(Instant v) { this.deletionRequestedAt = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
