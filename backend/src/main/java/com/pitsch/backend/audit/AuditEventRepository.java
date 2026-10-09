package com.pitsch.backend.audit;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long>, JpaSpecificationExecutor<AuditEvent> {

    // AuditEvent is @Immutable, so bulk deletes are native SQL (retention is the only way rows are removed).
    @Modifying
    @Query(value = "delete from audit_events where created_at < :cutoff", nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") Instant cutoff);

    List<AuditEvent> findTop5000ByOrganizationIdOrderByIdDesc(Long organizationId);

    List<AuditEvent> findTop2000ByActorUserIdOrderByIdDesc(Long actorUserId);
}
