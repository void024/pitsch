package com.pitsch.backend.workflow;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowRepository extends JpaRepository<Workflow, Long> {

    List<Workflow> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<Workflow> findByIdAndUserId(Long id, Long userId);

    List<Workflow> findByPitchIdOrderByCreatedAtDesc(Long pitchId);

    Optional<Workflow> findFirstByEmailId(Long emailId);
}
