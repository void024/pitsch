package com.pitsch.backend.billing;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "usage_records")
public class UsageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long organizationId;

    private Long userId;

    @Column(nullable = false, length = 40)
    private String metric;

    @Column(nullable = false)
    private long quantity;

    /** "YYYY-MM" in UTC — quotas are monthly. */
    @Column(nullable = false, length = 7)
    private String periodMonth;

    private Long workflowId;

    @Column(nullable = false)
    private Instant createdAt;

    protected UsageRecord() { }

    public UsageRecord(Long organizationId, Long userId, UsageMetric metric, long quantity, String periodMonth,
                       Long workflowId, Instant createdAt) {
        this.organizationId = organizationId;
        this.userId = userId;
        this.metric = metric.name();
        this.quantity = quantity;
        this.periodMonth = periodMonth;
        this.workflowId = workflowId;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getOrganizationId() { return organizationId; }
    public Long getUserId() { return userId; }
    public String getMetric() { return metric; }
    public long getQuantity() { return quantity; }
    public String getPeriodMonth() { return periodMonth; }
    public Long getWorkflowId() { return workflowId; }
    public Instant getCreatedAt() { return createdAt; }
}
