package com.pitsch.backend.event;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CalendarEventRepository extends JpaRepository<CalendarEvent, Long> {

    List<CalendarEvent> findByUserIdOrderByStartTimeAsc(Long userId);

    Optional<CalendarEvent> findByIdAndUserId(Long id, Long userId);

    /** Events overlapping [from, to). */
    List<CalendarEvent> findByUserIdAndEndTimeAfterAndStartTimeBeforeOrderByStartTimeAsc(Long userId, Instant from, Instant to);
}
