package com.pitsch.backend.task;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    Optional<Task> findByIdAndOrganizationId(Long id, Long organizationId);

    List<Task> findByOrganizationIdAndPitchIdOrderByCreatedAtDesc(Long organizationId, Long pitchId);

    long countByOrganizationIdAndStatusNot(Long organizationId, String status);

    long countByOrganizationIdAndAssigneeUserIdAndStatusNot(Long organizationId, Long assigneeUserId, String status);

    @Modifying
    @Query("update Task t set t.pitchId = null where t.organizationId = :orgId and t.pitchId = :pitchId")
    int detachPitch(@Param("orgId") Long orgId, @Param("pitchId") Long pitchId);
}
