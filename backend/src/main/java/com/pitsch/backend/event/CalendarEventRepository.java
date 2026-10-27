package com.pitsch.backend.event;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CalendarEventRepository extends JpaRepository<CalendarEvent, Long> {

    List<CalendarEvent> findByOrganizationIdAndEndTimeAfterAndStartTimeBeforeOrderByStartTimeAsc(Long organizationId,
                                                                                                  Instant from, Instant to);

    List<CalendarEvent> findByOrganizationIdOrderByStartTimeAsc(Long organizationId);

    Optional<CalendarEvent> findByIdAndOrganizationId(Long id, Long organizationId);

    /** The owner's busy time in [from, to) — manual events and Pitsch meetings that are not cancelled. */
    List<CalendarEvent> findByOrganizationIdAndUserIdAndEndTimeAfterAndStartTimeBeforeOrderByStartTimeAsc(
            Long organizationId, Long userId, Instant from, Instant to);

    List<CalendarEvent> findByWorkflowIdOrderByIdDesc(Long workflowId);

    List<CalendarEvent> findByOrganizationIdAndPitchIdOrderByStartTimeAsc(Long organizationId, Long pitchId);

    /** Pitsch-created meetings synced to an external calendar and still upcoming (sync checks). */
    List<CalendarEvent> findBySyncStatusAndEndTimeAfter(String syncStatus, Instant now);

    @Modifying
    @Query("update CalendarEvent e set e.workflowId = null where e.workflowId in :ids")
    int detachWorkflows(@Param("ids") Collection<Long> ids);
}
