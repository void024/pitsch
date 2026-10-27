package com.pitsch.backend.email;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailRepository extends JpaRepository<Email, Long> {

    Page<Email> findByOrganizationId(Long organizationId, Pageable pageable);

    Optional<Email> findByIdAndOrganizationId(Long id, Long organizationId);

    boolean existsByOrganizationIdAndDedupeKey(Long organizationId, String dedupeKey);

    Optional<Email> findByOrganizationIdAndDedupeKey(Long organizationId, String dedupeKey);

    List<Email> findByOrganizationId(Long organizationId);

    /** Retention: blank bodies of old emails (metadata stays so pitch history remains understandable). */
    @Modifying
    @Query("update Email e set e.body = '[removed by retention policy]' where e.organizationId = :orgId "
            + "and e.receivedAt < :cutoff and e.body <> '[removed by retention policy]'")
    int redactBodiesBefore(@Param("orgId") Long orgId, @Param("cutoff") Instant cutoff);
}
