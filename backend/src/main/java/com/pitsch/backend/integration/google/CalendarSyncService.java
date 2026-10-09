package com.pitsch.backend.integration.google;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.pitsch.backend.audit.AuditAction;
import com.pitsch.backend.audit.AuditService;
import com.pitsch.backend.event.CalendarEvent;
import com.pitsch.backend.event.CalendarEventRepository;
import com.pitsch.backend.integration.Integration;
import com.pitsch.backend.integration.IntegrationConnection;
import com.pitsch.backend.integration.IntegrationService;
import com.pitsch.backend.integration.ProviderRegistry;
import com.pitsch.backend.integration.provider.CalendarProvider;
import com.pitsch.backend.jobs.Job;
import com.pitsch.backend.jobs.JobHandler;
import com.pitsch.backend.jobs.JobQueue;
import com.pitsch.backend.jobs.JobType;
import com.pitsch.backend.notification.NotificationService;
import com.pitsch.backend.notification.NotificationType;
import com.pitsch.backend.workflow.WorkflowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Detects changes made directly in Google Calendar to meetings Pitsch created (moved or deleted by the investor or
 * the founder) and reflects them in Pitsch, notifying the owner. Pitsch never re-creates a meeting someone deleted.
 */
@Component
public class CalendarSyncService implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(CalendarSyncService.class);

    private final CalendarEventRepository events;
    private final WorkflowRepository workflows;
    private final IntegrationService integrations;
    private final ProviderRegistry providers;
    private final JobQueue jobs;
    private final NotificationService notifications;
    private final AuditService audit;
    private final Clock clock;

    public CalendarSyncService(CalendarEventRepository events, WorkflowRepository workflows, IntegrationService integrations,
                               ProviderRegistry providers, JobQueue jobs, NotificationService notifications,
                               AuditService audit, Clock clock) {
        this.events = events;
        this.workflows = workflows;
        this.integrations = integrations;
        this.providers = providers;
        this.jobs = jobs;
        this.notifications = notifications;
        this.audit = audit;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 30 * 60_000L, initialDelay = 120_000L)
    public void schedule() {
        if (providers.demo()) {
            return;
        }
        long slot = clock.millis() / (30 * 60_000L);
        jobs.enqueue(JobType.CALENDAR_SYNC, null, null, Map.of(), "calendar-sync:" + slot, Duration.ZERO);
    }

    @Override
    public JobType type() {
        return JobType.CALENDAR_SYNC;
    }

    @Override
    public void handle(Job job, JsonNode payload) {
        List<CalendarEvent> upcoming = events.findBySyncStatusAndEndTimeAfter(CalendarEvent.SyncStatus.SYNCED.name(),
                clock.instant().minus(Duration.ofHours(1)));
        for (CalendarEvent e : upcoming) {
            try {
                check(e);
            } catch (RuntimeException ex) {
                log.info("Calendar sync check failed for event {}: {}", e.getId(), ex.getMessage());
            }
        }
    }

    private void check(CalendarEvent e) {
        IntegrationConnection conn = integrations.usable(e.getOrganizationId(), e.getUserId(), Integration.CALENDAR).orElse(null);
        if (conn == null || e.getExternalEventId() == null) {
            return;
        }
        CalendarProvider.EventState st = providers.calendar().get(conn, e.getExternalCalendarId(), e.getExternalEventId());
        if (!st.exists() || st.cancelled()) {
            e.setSyncStatus(CalendarEvent.SyncStatus.CANCELLED_EXTERNALLY.name());
            e.setCancelledAt(clock.instant());
            e.setLastSyncedAt(clock.instant());
            events.save(e);
            clearWorkflowMeeting(e);
            audit.recordAi(e.getOrganizationId(), AuditAction.MEETING_CHANGED_EXTERNALLY, "CALENDAR_EVENT", e.getId(),
                    Map.of("change", "cancelled"));
            notifications.notify(e.getOrganizationId(), e.getUserId(), NotificationType.MEETING_CHANGED, "Meeting cancelled in Google Calendar",
                    "\"" + e.getTitle() + "\" was removed from Google Calendar.", e.getWorkflowId(), "event:" + e.getId() + ":cancelled-ext");
            return;
        }
        boolean moved = st.start() != null && st.end() != null
                && (!Objects.equals(st.start(), e.getStartTime()) || !Objects.equals(st.end(), e.getEndTime()));
        if (moved) {
            e.setStartTime(st.start());
            e.setEndTime(st.end());
            e.setLastSyncedAt(clock.instant());
            events.save(e);
            if (e.getWorkflowId() != null) {
                workflows.findById(e.getWorkflowId()).ifPresent(w -> {
                    if (e.getId().equals(w.getMeetingEventId())) {
                        w.setMeetingStart(st.start());
                        w.setMeetingEnd(st.end());
                        workflows.save(w);
                    }
                });
            }
            audit.recordAi(e.getOrganizationId(), AuditAction.MEETING_CHANGED_EXTERNALLY, "CALENDAR_EVENT", e.getId(),
                    Map.of("change", "moved"));
            notifications.notify(e.getOrganizationId(), e.getUserId(), NotificationType.MEETING_CHANGED, "Meeting moved in Google Calendar",
                    "\"" + e.getTitle() + "\" now starts at " + st.start() + ".", e.getWorkflowId(),
                    "event:" + e.getId() + ":moved:" + st.start().getEpochSecond());
        } else {
            e.setLastSyncedAt(clock.instant());
            events.save(e);
        }
    }

    private void clearWorkflowMeeting(CalendarEvent e) {
        if (e.getWorkflowId() == null) {
            return;
        }
        workflows.findById(e.getWorkflowId()).ifPresent(w -> {
            if (e.getId().equals(w.getMeetingEventId())) {
                w.setMeetingStart(null);
                w.setMeetingEnd(null);
                w.setMeetingEventId(null);
                workflows.save(w);
            }
        });
    }
}
