package com.pitsch.backend.billing;

import java.util.EnumMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.ErrorCode;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.files.StoredFileRepository;
import com.pitsch.backend.org.MembershipRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single place that answers "may this workspace do X this month?". Limits come from the plan row in the
 * database; nothing else in the code base hard-codes a paid limit.
 */
@Service
public class EntitlementService {

    public static final long UNLIMITED = -1;

    public record Quota(UsageMetric metric, long used, long limit) {
        public boolean unlimited() { return limit == UNLIMITED; }
    }

    private final SubscriptionRepository subscriptions;
    private final PlanRepository plans;
    private final UsageService usage;
    private final MembershipRepository memberships;
    private final StoredFileRepository storedFiles;
    private final Json json;

    public EntitlementService(SubscriptionRepository subscriptions, PlanRepository plans, UsageService usage,
                              MembershipRepository memberships, StoredFileRepository storedFiles, Json json) {
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.usage = usage;
        this.memberships = memberships;
        this.storedFiles = storedFiles;
        this.json = json;
    }

    /** Current consumption: members and storage are point-in-time; everything else is this month's usage. */
    @Transactional(readOnly = true)
    public long used(Long orgId, UsageMetric metric) {
        return switch (metric) {
            case MEMBERS -> memberships.countByOrganizationId(orgId);
            case STORAGE_MB -> storedFiles.totalBytes(orgId) / (1024 * 1024);
            default -> usage.total(orgId, metric);
        };
    }

    @Transactional(readOnly = true)
    public String planCode(Long orgId) {
        return subscriptions.findByOrganizationId(orgId).map(Subscription::effectivePlanCode).orElse("FREE");
    }

    @Transactional(readOnly = true)
    public long limit(Long orgId, UsageMetric metric) {
        Plan plan = plans.findById(planCode(orgId)).orElseGet(() -> plans.findById("FREE").orElse(null));
        if (plan == null) {
            return UNLIMITED;
        }
        JsonNode limits = json.read(plan.getLimitsJson());
        return limits == null || !limits.has(metric.name()) ? UNLIMITED : limits.path(metric.name()).asLong(UNLIMITED);
    }

    /** Non-throwing check (safe inside a transaction that must continue either way). */
    @Transactional(readOnly = true)
    public boolean allows(Long orgId, UsageMetric metric, long amount) {
        long limit = limit(orgId, metric);
        if (limit == UNLIMITED) {
            return true;
        }
        return used(orgId, metric) + amount <= limit;
    }

    /** Throws QUOTA_EXCEEDED (HTTP 402) if adding {@code amount} would exceed the monthly limit. */
    @Transactional(readOnly = true)
    public void require(Long orgId, UsageMetric metric, long amount) {
        long limit = limit(orgId, metric);
        if (limit == UNLIMITED) {
            return;
        }
        long used = used(orgId, metric);
        if (used + amount > limit) {
            throw new ApiException(ErrorCode.QUOTA_EXCEEDED,
                    "Your plan's monthly limit for " + metric.name().toLowerCase().replace('_', ' ')
                            + " is reached (" + limit + "). Upgrade or wait for the next billing month.",
                    Map.of("metric", metric.name(), "used", used, "limit", limit));
        }
    }

    @Transactional(readOnly = true)
    public Map<UsageMetric, Quota> quotas(Long orgId) {
        Map<UsageMetric, Long> totals = usage.totals(orgId);
        Map<UsageMetric, Quota> out = new EnumMap<>(UsageMetric.class);
        for (UsageMetric m : UsageMetric.values()) {
            long used = m == UsageMetric.MEMBERS || m == UsageMetric.STORAGE_MB ? used(orgId, m) : totals.getOrDefault(m, 0L);
            out.put(m, new Quota(m, used, limit(orgId, m)));
        }
        return out;
    }
}
