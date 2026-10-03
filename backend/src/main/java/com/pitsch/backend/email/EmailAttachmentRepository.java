package com.pitsch.backend.email;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailAttachmentRepository extends JpaRepository<EmailAttachment, Long> {

    List<EmailAttachment> findByEmailIdOrderByIdAsc(Long emailId);
}
