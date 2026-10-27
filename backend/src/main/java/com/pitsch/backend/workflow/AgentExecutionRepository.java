package com.pitsch.backend.workflow;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AgentExecutionRepository extends JpaRepository<AgentExecution, Long> {

    List<AgentExecution> findByWorkflowIdOrderByIdAsc(Long workflowId);

    long countByWorkflowId(Long workflowId);

    /** Rows: agentName, calls, total tokens (nullable), total cost (nullable), average latency ms (nullable). */
    @Query("select a.agentName, count(a), sum(a.promptTokens + a.completionTokens), "
            + "sum(a.estimatedCostUsd), avg(a.latencyMs) "
            + "from AgentExecution a where a.organizationId = :orgId and a.startedAt >= :from group by a.agentName")
    List<Object[]> statsSince(@Param("orgId") Long orgId, @Param("from") Instant from);

    /** Privacy retention: agent inputs/outputs are evidence for debugging, not a long-term store. */
    @Modifying
    @Query("update AgentExecution a set a.inputJson = null, a.outputJson = null where a.startedAt < :cutoff "
            + "and (a.inputJson is not null or a.outputJson is not null)")
    int purgePayloadsBefore(@Param("cutoff") Instant cutoff);

    @Modifying
    @Query("delete from AgentExecution a where a.workflowId in :ids")
    int deleteByWorkflowIds(@Param("ids") Collection<Long> ids);
}
