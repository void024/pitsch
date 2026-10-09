package com.pitsch.backend.integration;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Server-side OAuth state (CSRF protection) and encrypted PKCE verifier for one authorization round-trip. */
@Entity
@Table(name = "oauth_states")
public class OAuthState {

    public enum Purpose { CONNECT, LOGIN }

    @Id
    @Column(length = 64)
    private String stateHash;

    @Column(nullable = false, length = 20)
    private String purpose;

    private Long organizationId;
    private Long userId;

    @Column(nullable = false, length = 100)
    private String integrations;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String codeVerifierEnc;

    @Column(length = 200)
    private String returnPath;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant usedAt;

    public String getStateHash() { return stateHash; }
    public void setStateHash(String v) { this.stateHash = v; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String v) { this.purpose = v; }
    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long v) { this.organizationId = v; }
    public Long getUserId() { return userId; }
    public void setUserId(Long v) { this.userId = v; }
    public String getIntegrations() { return integrations; }
    public void setIntegrations(String v) { this.integrations = v; }
    public String getCodeVerifierEnc() { return codeVerifierEnc; }
    public void setCodeVerifierEnc(String v) { this.codeVerifierEnc = v; }
    public String getReturnPath() { return returnPath; }
    public void setReturnPath(String v) { this.returnPath = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { this.expiresAt = v; }
    public Instant getUsedAt() { return usedAt; }
    public void setUsedAt(Instant v) { this.usedAt = v; }
}
