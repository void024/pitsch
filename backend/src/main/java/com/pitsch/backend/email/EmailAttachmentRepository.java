package com.pitsch.backend.email;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailAttachmentRepository extends JpaRepository<EmailAttachment, Long> {

    List<EmailAttachment> findByEmailIdOrderByIdAsc(Long emailId);

    List<EmailAttachment> findByEmailIdIn(Collection<Long> emailIds);

    List<EmailAttachment> findByOrganizationId(Long organizationId);
}
