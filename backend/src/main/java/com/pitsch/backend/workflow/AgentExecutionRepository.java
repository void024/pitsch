package com.pitsch.backend.workflow;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentExecutionRepository extends JpaRepository<AgentExecution, Long> {

    List<AgentExecution> findByWorkflowIdOrderByIdAsc(Long workflowId);

    long countByWorkflowId(Long workflowId);
}
