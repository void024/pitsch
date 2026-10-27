package com.pitsch.backend.billing;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Records metered usage (per workspace, per user, per month). */
@Service
public class UsageService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC);

    private final UsageRecordRepository repo;
    private final Clock clock;

    public UsageService(UsageRecordRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    public String currentPeriod() {
        return MONTH.format(clock.instant());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long orgId, Long userId, UsageMetric metric, long quantity, Long workflowId) {
        if (quantity <= 0) {
            return;
        }
        repo.save(new UsageRecord(orgId, userId, metric, quantity, currentPeriod(), workflowId, clock.instant()));
    }

    @Transactional(readOnly = true)
    public long total(Long orgId, UsageMetric metric) {
        return repo.total(orgId, metric.name(), currentPeriod());
    }

    @Transactional(readOnly = true)
    public Map<UsageMetric, Long> totals(Long orgId) {
        Map<UsageMetric, Long> out = new EnumMap<>(UsageMetric.class);
        for (UsageMetric m : UsageMetric.values()) {
            out.put(m, 0L);
        }
        for (Object[] row : repo.totalsByMetric(orgId, currentPeriod())) {
            try {
                out.put(UsageMetric.valueOf((String) row[0]), ((Number) row[1]).longValue());
            } catch (IllegalArgumentException ignored) {
                // unknown historic metric
            }
        }
        return out;
    }
}
