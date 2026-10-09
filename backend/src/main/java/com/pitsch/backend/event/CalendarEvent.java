package com.pitsch.backend.event;

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
 * A calendar entry in Pitsch: manual events, and pitch meetings created through an approved CREATE_MEETING action
 * (mirrored in Google Calendar with its external event ID and sync state).
 */
@Entity
@Table(name = "calendar_events")
public class CalendarEvent {

    /** LOCAL_ONLY (Pitsch-only entry) | SYNCED | CANCELLED | CANCELLED_EXTERNALLY | CHANGED_EXTERNALLY | DEMO */
    public enum SyncStatus { LOCAL_ONLY, SYNCED, CANCELLED, CANCELLED_EXTERNALLY, CHANGED_EXTERNALLY, DEMO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long organizationId;

    /** Owner (whose calendar). */
    private Long userId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String location;

    @Column(nullable = false)
    private Instant startTime;

    @Column(nullable = false)
    private Instant endTime;

    private Long pitchId;
    private Long workflowId;

    /** MANUAL | PITSCH (approved pitch meeting) */
    private String source = "MANUAL";

    /** PITSCH (local) | GOOGLE | DEMO */
    @Column(nullable = false, length = 20)
    private String provider = "PITSCH";

    private String externalCalendarId;
    private String externalEventId;

    @Column(nullable = false, length = 30)
    private String syncStatus = SyncStatus.LOCAL_ONLY.name();

    @Column(length = 500)
    private String conferenceLink;

    @Column(length = 500)
    private String htmlLink;

    @Column(columnDefinition = "TEXT")
    private String attendeesJson;

    private Long createdByUserId;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant cancelledAt;
    private Instant lastSyncedAt;

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
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public Instant getStartTime() { return startTime; }
    public void setStartTime(Instant startTime) { this.startTime = startTime; }
    public Instant getEndTime() { return endTime; }
    public void setEndTime(Instant endTime) { this.endTime = endTime; }
    public Long getPitchId() { return pitchId; }
    public void setPitchId(Long pitchId) { this.pitchId = pitchId; }
    public Long getWorkflowId() { return workflowId; }
    public void setWorkflowId(Long workflowId) { this.workflowId = workflowId; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getProvider() { return provider; }
    public void setProvider(String v) { this.provider = v; }
    public String getExternalCalendarId() { return externalCalendarId; }
    public void setExternalCalendarId(String v) { this.externalCalendarId = v; }
    public String getExternalEventId() { return externalEventId; }
    public void setExternalEventId(String v) { this.externalEventId = v; }
    public String getSyncStatus() { return syncStatus; }
    public void setSyncStatus(String v) { this.syncStatus = v; }
    public String getConferenceLink() { return conferenceLink; }
    public void setConferenceLink(String v) { this.conferenceLink = v; }
    public String getHtmlLink() { return htmlLink; }
    public void setHtmlLink(String v) { this.htmlLink = v; }
    public String getAttendeesJson() { return attendeesJson; }
    public void setAttendeesJson(String v) { this.attendeesJson = v; }
    public Long getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(Long v) { this.createdByUserId = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant v) { this.cancelledAt = v; }
    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant v) { this.lastSyncedAt = v; }
    public long getVersion() { return version; }
}
