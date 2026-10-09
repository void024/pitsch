package com.pitsch.backend.pitch;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PitchRepository extends JpaRepository<Pitch, Long>, JpaSpecificationExecutor<Pitch> {

    Optional<Pitch> findByIdAndOrganizationId(Long id, Long organizationId);

    List<Pitch> findByOrganizationId(Long organizationId);

    Optional<Pitch> findFirstByOrganizationIdAndFirstEmailId(Long organizationId, Long firstEmailId);

    /** Placeholder for "no domain / no thread" so no nullable parameters are bound (portable across databases). */
    String NO_MATCH = "~none~";

    /**
     * Follow-up candidates: same founder email, same company domain, or one of the thread IDs — bounded and indexed
     * instead of loading the whole pipeline. Pass {@link #NO_MATCH} for an absent domain or thread.
     */
    @Query("select p from Pitch p where p.organizationId = :orgId and (lower(p.founderEmail) = :sender "
            + "or lower(p.companyDomain) = :domain "
            + "or p.threadIds like concat('%', :threadId, '%')) order by p.lastActivityAt desc")
    List<Pitch> findCandidates(@Param("orgId") Long orgId, @Param("sender") String sender, @Param("domain") String domain,
                               @Param("threadId") String threadId, Pageable limit);

    /** Recently active pitches whose company name might be mentioned in the email (checked in code). */
    @Query("select p from Pitch p where p.organizationId = :orgId and p.companyName is not null order by p.lastActivityAt desc")
    List<Pitch> findRecent(@Param("orgId") Long orgId, Pageable limit);

    @Query("select p.dealStage, count(p) from Pitch p where p.organizationId = :orgId group by p.dealStage")
    List<Object[]> countByDealStage(@Param("orgId") Long orgId);

    long countByOrganizationIdAndIdIn(Long organizationId, Collection<Long> ids);

    @Modifying
    @Query("update Pitch p set p.latestWorkflowId = null where p.organizationId = :orgId and p.latestWorkflowId in :ids")
    int clearLatestWorkflow(@Param("orgId") Long orgId, @Param("ids") java.util.Collection<Long> ids);

    @Modifying
    @Query("update Pitch p set p.latestBriefWorkflowId = null where p.organizationId = :orgId and p.latestBriefWorkflowId in :ids")
    int clearLatestBriefWorkflow(@Param("orgId") Long orgId, @Param("ids") java.util.Collection<Long> ids);
}
