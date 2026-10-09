package com.pitsch.backend.integration;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

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
 * One member's Google account linked to one workspace. Tokens are AES-GCM encrypted (TokenCipher) and are never
 * returned by any API, logged, or sent to the AI service.
 */
@Entity
@Table(name = "integration_connections")
public class IntegrationConnection {

    public enum Status { CONNECTED, NEEDS_RECONNECT, ERROR, REVOKED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long organizationId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 20)
    private String provider = "GOOGLE";

    @Column(length = 320)
    private String accountEmail;

    @Column(columnDefinition = "TEXT")
    private String grantedScopes;

    /** Comma-separated {@link Integration} names the user turned on. */
    @Column(nullable = false, length = 100)
    private String enabledIntegrations = "";

    @Column(columnDefinition = "TEXT")
    private String accessTokenEnc;

    @Column(columnDefinition = "TEXT")
    private String refreshTokenEnc;

    private Instant accessTokenExpiresAt;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 1000)
    private String lastError;

    private Instant lastSyncAt;

    @Column(length = 20)
    private String lastSyncStatus;

    @Column(length = 40)
    private String gmailHistoryId;

    private Instant gmailWatchExpiresAt;

    private String calendarId;

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

    public Set<Integration> enabled() {
        if (enabledIntegrations == null || enabledIntegrations.isBlank()) {
            return EnumSet.noneOf(Integration.class);
        }
        return Arrays.stream(enabledIntegrations.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(Integration::valueOf).collect(Collectors.toCollection(() -> EnumSet.noneOf(Integration.class)));
    }

    public void setEnabled(Set<Integration> integrations) {
        this.enabledIntegrations = integrations.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    public boolean isUsableFor(Integration integration) {
        return Status.CONNECTED.name().equals(status) && enabled().contains(integration);
    }

    public Long getId() { return id; }
    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long v) { this.organizationId = v; }
    public Long getUserId() { return userId; }
    public void setUserId(Long v) { this.userId = v; }
    public String getProvider() { return provider; }
    public void setProvider(String v) { this.provider = v; }
    public String getAccountEmail() { return accountEmail; }
    public void setAccountEmail(String v) { this.accountEmail = v; }
    public String getGrantedScopes() { return grantedScopes; }
    public void setGrantedScopes(String v) { this.grantedScopes = v; }
    public String getEnabledIntegrations() { return enabledIntegrations; }
    public String getAccessTokenEnc() { return accessTokenEnc; }
    public void setAccessTokenEnc(String v) { this.accessTokenEnc = v; }
    public String getRefreshTokenEnc() { return refreshTokenEnc; }
    public void setRefreshTokenEnc(String v) { this.refreshTokenEnc = v; }
    public Instant getAccessTokenExpiresAt() { return accessTokenExpiresAt; }
    public void setAccessTokenExpiresAt(Instant v) { this.accessTokenExpiresAt = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getLastError() { return lastError; }
    public void setLastError(String v) { this.lastError = v; }
    public Instant getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(Instant v) { this.lastSyncAt = v; }
    public String getLastSyncStatus() { return lastSyncStatus; }
    public void setLastSyncStatus(String v) { this.lastSyncStatus = v; }
    public String getGmailHistoryId() { return gmailHistoryId; }
    public void setGmailHistoryId(String v) { this.gmailHistoryId = v; }
    public Instant getGmailWatchExpiresAt() { return gmailWatchExpiresAt; }
    public void setGmailWatchExpiresAt(Instant v) { this.gmailWatchExpiresAt = v; }
    public String getCalendarId() { return calendarId; }
    public void setCalendarId(String v) { this.calendarId = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
