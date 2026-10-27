package com.pitsch.backend.workflow;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowRepository extends JpaRepository<Workflow, Long>, JpaSpecificationExecutor<Workflow> {

    Optional<Workflow> findByIdAndOrganizationId(Long id, Long organizationId);

    List<Workflow> findByPitchIdOrderByCreatedAtDesc(Long pitchId);

    List<Workflow> findByPitchIdAndOrganizationIdOrderByCreatedAtDesc(Long pitchId, Long organizationId);

    Optional<Workflow> findFirstByEmailId(Long emailId);

    List<Workflow> findByOrganizationId(Long organizationId);

    List<Workflow> findByIdIn(Collection<Long> ids);

    long countByOrganizationIdAndStatusIn(Long organizationId, Collection<String> statuses);

    /** Workflows stuck in a "working" state without a live job (crash recovery). */
    @Query("select w from Workflow w where w.status in ('RECEIVED', 'CLASSIFYING', 'PROCESSING') and w.updatedAt < :cutoff")
    List<Workflow> findStuck(@Param("cutoff") Instant cutoff);

    /** Detach workflow history from a deleted pitch (workflows stay for the audit trail). */
    @Modifying
    @Query("update Workflow w set w.pitchId = null where w.organizationId = :orgId and w.pitchId = :pitchId")
    int detachPitch(@Param("orgId") Long orgId, @Param("pitchId") Long pitchId);

    long countByOrganizationIdAndPitchIdAndStatusIn(Long organizationId, Long pitchId, Collection<String> statuses);

    /** Retention: drop raw agent payloads of old workflows; the brief (analysis) is kept. */
    @Modifying
    @Query("update Workflow w set w.documentJson = null, w.researchJson = null, w.verificationJson = null, "
            + "w.classificationJson = null where w.organizationId = :orgId and w.createdAt < :cutoff "
            + "and w.status in ('COMPLETED', 'STOPPED', 'NOT_PITCH')")
    int purgeIntermediateOutputsBefore(@Param("orgId") Long orgId, @Param("cutoff") Instant cutoff);

    List<Workflow> findByOrganizationIdAndEmailId(Long organizationId, Long emailId);
}
