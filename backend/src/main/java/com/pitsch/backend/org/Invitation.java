package com.pitsch.backend.org;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "invitations")
public class Invitation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = 320)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    /** SHA-256 of the token sent by email; the token itself is never stored. */
    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    private Long invitedByUserId;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant acceptedAt;
    private Instant revokedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public boolean isPending(Instant now) {
        return acceptedAt == null && revokedAt == null && expiresAt.isAfter(now);
    }

    public Long getId() { return id; }
    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long v) { this.organizationId = v; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String v) { this.tokenHash = v; }
    public Long getInvitedByUserId() { return invitedByUserId; }
    public void setInvitedByUserId(Long v) { this.invitedByUserId = v; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { this.expiresAt = v; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(Instant v) { this.acceptedAt = v; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant v) { this.revokedAt = v; }
    public Instant getCreatedAt() { return createdAt; }
}
