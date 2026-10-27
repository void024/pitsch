package com.pitsch.backend.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A purchasable plan. Limits are data (limits_json) so pricing changes need no code change. */
@Entity
@Table(name = "plans")
public class Plan {

    @Id
    @Column(length = 32)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private int monthlyPriceCents;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String limitsJson;

    @Column(length = 100)
    private String stripePriceId;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private int sortOrder;

    public String getCode() { return code; }
    public String getName() { return name; }
    public int getMonthlyPriceCents() { return monthlyPriceCents; }
    public String getLimitsJson() { return limitsJson; }
    public String getStripePriceId() { return stripePriceId; }
    public boolean isActive() { return active; }
    public int getSortOrder() { return sortOrder; }
}
