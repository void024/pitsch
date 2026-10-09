package com.pitsch.backend.billing;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UsageRecordRepository extends JpaRepository<UsageRecord, Long> {

    @Query("select coalesce(sum(u.quantity), 0) from UsageRecord u "
            + "where u.organizationId = :orgId and u.metric = :metric and u.periodMonth = :period")
    long total(@Param("orgId") Long orgId, @Param("metric") String metric, @Param("period") String period);

    @Query("select u.metric, coalesce(sum(u.quantity), 0) from UsageRecord u "
            + "where u.organizationId = :orgId and u.periodMonth = :period group by u.metric")
    List<Object[]> totalsByMetric(@Param("orgId") Long orgId, @Param("period") String period);
}
