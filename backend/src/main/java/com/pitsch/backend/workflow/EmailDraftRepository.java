package com.pitsch.backend.workflow;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailDraftRepository extends JpaRepository<EmailDraft, Long> {

    Optional<EmailDraft> findFirstByWorkflowIdOrderByIdDesc(Long workflowId);

    Optional<EmailDraft> findByIdAndUserId(Long id, Long userId);
}
