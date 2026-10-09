package com.pitsch.backend.auth;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A signed-in device. Access tokens carry its ID; revoking it signs the device out within one request. */
@Entity
@Table(name = "sessions")
public class Session {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false)
    private Long userId;

    /** The workspace this session is working in — the only source of tenant scope for API calls. */
    private Long activeOrganizationId;

    @Column(length = 400)
    private String userAgent;

    @Column(length = 64)
    private String ipAddress;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant lastSeenAt;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant revokedAt;

    @Column(length = 40)
    private String revokeReason;

    public boolean isActive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getActiveOrganizationId() { return activeOrganizationId; }
    public void setActiveOrganizationId(Long v) { this.activeOrganizationId = v; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String v) { this.userAgent = v; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String v) { this.ipAddress = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant v) { this.lastSeenAt = v; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { this.expiresAt = v; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant v) { this.revokedAt = v; }
    public String getRevokeReason() { return revokeReason; }
    public void setRevokeReason(String v) { this.revokeReason = v; }
}
