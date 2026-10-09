package com.pitsch.backend.approval;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApprovalRepository extends JpaRepository<Approval, Long> {

    Optional<Approval> findByIdAndOrganizationId(Long id, Long organizationId);

    Optional<Approval> findByIdempotencyKey(String idempotencyKey);

    Page<Approval> findByOrganizationIdAndStatus(Long organizationId, String status, Pageable pageable);

    Page<Approval> findByOrganizationId(Long organizationId, Pageable pageable);

    List<Approval> findByOrganizationIdAndPitchIdAndActionTypeAndStatus(Long organizationId, Long pitchId,
                                                                        String actionType, String status);

    List<Approval> findByWorkflowIdOrderByIdDesc(Long workflowId);

    long countByOrganizationIdAndStatus(Long organizationId, String status);

    @Modifying
    @Query("delete from Approval a where a.workflowId in :ids")
    int deleteByWorkflowIds(@Param("ids") Collection<Long> ids);

    long countByWorkflowIdInAndStatusIn(Collection<Long> workflowIds, Collection<String> statuses);
}
