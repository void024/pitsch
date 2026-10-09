package com.pitsch.backend.billing;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * A workspace's plan. Status only changes from verified billing-provider webhooks (or an operator) — the client can
 * never set it, and Pitsch never assumes a payment succeeded.
 */
@Entity
@Table(name = "subscriptions")
public class Subscription {

    public enum Status { ACTIVE, TRIALING, PAST_DUE, CANCELED, INCOMPLETE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long organizationId;

    @Column(nullable = false, length = 32)
    private String planCode;

    @Column(nullable = false, length = 20)
    private String status;

    /** NONE (plan assigned without a billing provider) | STRIPE */
    @Column(nullable = false, length = 20)
    private String provider;

    @Column(length = 100)
    private String providerCustomerId;

    @Column(length = 100)
    private String providerSubscriptionId;

    private Instant currentPeriodStart;
    private Instant currentPeriodEnd;

    @Column(nullable = false)
    private boolean cancelAtPeriodEnd;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
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

    /**
     * Plan whose limits apply. ACTIVE and TRIALING use the plan; PAST_DUE keeps it while the payment provider retries
     * (dunning); CANCELED / INCOMPLETE fall back to FREE.
     */
    public String effectivePlanCode() {
        return Status.ACTIVE.name().equals(status) || Status.TRIALING.name().equals(status)
                || Status.PAST_DUE.name().equals(status) ? planCode : "FREE";
    }

    public Long getId() { return id; }
    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long v) { this.organizationId = v; }
    public String getPlanCode() { return planCode; }
    public void setPlanCode(String v) { this.planCode = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getProvider() { return provider; }
    public void setProvider(String v) { this.provider = v; }
    public String getProviderCustomerId() { return providerCustomerId; }
    public void setProviderCustomerId(String v) { this.providerCustomerId = v; }
    public String getProviderSubscriptionId() { return providerSubscriptionId; }
    public void setProviderSubscriptionId(String v) { this.providerSubscriptionId = v; }
    public Instant getCurrentPeriodStart() { return currentPeriodStart; }
    public void setCurrentPeriodStart(Instant v) { this.currentPeriodStart = v; }
    public Instant getCurrentPeriodEnd() { return currentPeriodEnd; }
    public void setCurrentPeriodEnd(Instant v) { this.currentPeriodEnd = v; }
    public boolean isCancelAtPeriodEnd() { return cancelAtPeriodEnd; }
    public void setCancelAtPeriodEnd(boolean v) { this.cancelAtPeriodEnd = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
