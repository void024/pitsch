package com.pitsch.backend.auth;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Single-use, expiring token sent by email (verification, password reset, email change). Stored hashed. */
@Entity
@Table(name = "user_tokens")
public class UserToken {

    public enum Purpose { VERIFY_EMAIL, RESET_PASSWORD, CHANGE_EMAIL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Purpose purpose;

    @Column(nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(length = 320)
    private String newEmail;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant usedAt;

    @Column(nullable = false)
    private Instant createdAt;

    public boolean isUsable(Instant now) {
        return usedAt == null && expiresAt.isAfter(now);
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long v) { this.userId = v; }
    public Purpose getPurpose() { return purpose; }
    public void setPurpose(Purpose v) { this.purpose = v; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String v) { this.tokenHash = v; }
    public String getNewEmail() { return newEmail; }
    public void setNewEmail(String v) { this.newEmail = v; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { this.expiresAt = v; }
    public Instant getUsedAt() { return usedAt; }
    public void setUsedAt(Instant v) { this.usedAt = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
