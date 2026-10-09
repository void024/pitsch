package com.pitsch.backend.jobs;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobRepository extends JpaRepository<Job, Long> {

    @Query("select j.id from Job j where j.status = 'QUEUED' and j.runAt <= :now order by j.priority desc, j.runAt asc")
    List<Long> findRunnable(@Param("now") Instant now, Pageable limit);

    /** Atomic claim: only one worker (on any instance) can move a job from QUEUED to RUNNING. */
    @Modifying
    @Query("update Job j set j.status = 'RUNNING', j.lockedBy = :worker, j.lockedAt = :now, j.attempts = j.attempts + 1, "
            + "j.updatedAt = :now where j.id = :id and j.status = 'QUEUED'")
    int claim(@Param("id") Long id, @Param("worker") String worker, @Param("now") Instant now);

    @Query("select j from Job j where j.status = 'RUNNING' and j.lockedAt < :cutoff")
    List<Job> findStale(@Param("cutoff") Instant cutoff);

    long countByStatus(String status);

    boolean existsByDedupeKey(String dedupeKey);

    List<Job> findByWorkflowIdAndStatusIn(Long workflowId, List<String> statuses);

    @Modifying
    @Query("delete from Job j where j.status in ('SUCCEEDED', 'CANCELLED') and j.completedAt < :cutoff")
    int deleteFinishedBefore(@Param("cutoff") Instant cutoff);

    /** Workspace deletion: drop the workspace's queued/finished jobs except the deletion job itself. */
    @Modifying
    @Query("delete from Job j where j.organizationId = :orgId and j.id <> :keepId")
    int deleteForOrganizationExcept(@Param("orgId") Long orgId, @Param("keepId") Long keepId);

    long countByWorkflowIdInAndStatus(Collection<Long> workflowIds, String status);

    @Modifying
    @Query("delete from Job j where j.workflowId in :ids and j.status <> 'RUNNING'")
    int deleteNotRunningForWorkflows(@Param("ids") Collection<Long> ids);
}
