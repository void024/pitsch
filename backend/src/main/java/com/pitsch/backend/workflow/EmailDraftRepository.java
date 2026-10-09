package com.pitsch.backend.workflow;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailDraftRepository extends JpaRepository<EmailDraft, Long> {

    Optional<EmailDraft> findFirstByWorkflowIdOrderByIdDesc(Long workflowId);

    Optional<EmailDraft> findByIdAndOrganizationId(Long id, Long organizationId);

    List<EmailDraft> findByWorkflowIdOrderByIdDesc(Long workflowId);

    List<EmailDraft> findByOrganizationId(Long organizationId);

    List<EmailDraft> findByWorkflowIdIn(java.util.Collection<Long> workflowIds);

    @Modifying
    @Query("delete from EmailDraft d where d.workflowId in :ids")
    int deleteByWorkflowIds(@Param("ids") java.util.Collection<Long> ids);
}
