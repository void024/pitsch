package com.pitsch.backend.notification;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, Long> {

    List<NotificationPreference> findByUserIdAndOrganizationId(Long userId, Long organizationId);

    Optional<NotificationPreference> findByUserIdAndOrganizationIdAndType(Long userId, Long organizationId, String type);
}
