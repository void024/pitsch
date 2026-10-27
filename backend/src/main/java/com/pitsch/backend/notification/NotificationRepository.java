package com.pitsch.backend.notification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findTop50ByUserIdAndOrganizationIdOrderByCreatedAtDesc(Long userId, Long organizationId);

    Page<Notification> findByUserIdAndOrganizationId(Long userId, Long organizationId, Pageable pageable);

    Page<Notification> findByUserIdAndOrganizationIdAndReadFalse(Long userId, Long organizationId, Pageable pageable);

    long countByUserIdAndOrganizationIdAndReadFalse(Long userId, Long organizationId);

    Optional<Notification> findByIdAndUserIdAndOrganizationId(Long id, Long userId, Long organizationId);

    boolean existsByUserIdAndDedupeKey(Long userId, String dedupeKey);

    @Modifying
    @Query("update Notification n set n.read = true, n.readAt = :now where n.userId = :userId "
            + "and n.organizationId = :orgId and n.read = false")
    int markAllRead(@Param("userId") Long userId, @Param("orgId") Long orgId, @Param("now") Instant now);

    List<Notification> findByUserIdOrderByCreatedAtDesc(Long userId);

    @Modifying
    @Query("delete from Notification n where n.userId = :userId")
    int deleteByUser(@Param("userId") Long userId);

    @Modifying
    @Query("update Notification n set n.workflowId = null where n.workflowId in :ids")
    int detachWorkflows(@Param("ids") java.util.Collection<Long> ids);
}
