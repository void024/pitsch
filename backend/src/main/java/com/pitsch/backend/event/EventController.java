package com.pitsch.backend.event;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;

import com.pitsch.backend.activity.ActivityService;
import com.pitsch.backend.auth.AuthPrincipal;
import com.pitsch.backend.auth.Permission;
import com.pitsch.backend.auth.RequiresPermission;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.user.SettingsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Pitsch calendar: manual entries plus approved pitch meetings. Pitch meetings are managed through the workflow
 * (approve / cancel) so they stay in sync with Google Calendar; they cannot be edited here.
 */
@RestController
public class EventController {

    public record EventInput(@Size(max = 255) String title, @Size(max = 5000) String description,
                             @Size(max = 255) String location, String startTime, String endTime) { }

    public record EventView(Long id, String title, String description, String location, Instant startTime,
                            Instant endTime, String source, String provider, String syncStatus, String conferenceLink,
                            String htmlLink, Long pitchId, Long workflowId, Long userId) { }

    private final CalendarEventRepository repo;
    private final ActivityService activity;
    private final SettingsService settings;

    public EventController(CalendarEventRepository repo, ActivityService activity, SettingsService settings) {
        this.repo = repo;
        this.activity = activity;
        this.settings = settings;
    }

    @GetMapping({"/api/events", "/api/v1/events"})
    @RequiresPermission(Permission.CALENDAR_READ)
    public List<EventView> list(AuthPrincipal principal, @RequestParam(required = false) String from,
                                @RequestParam(required = false) String to) {
        ZoneId zone = ZoneId.of(settings.timezone(principal.userId()));
        List<CalendarEvent> events = from != null && to != null
                ? repo.findByOrganizationIdAndEndTimeAfterAndStartTimeBeforeOrderByStartTimeAsc(principal.orgId(),
                        parseInstant(from, zone, "from"), parseInstant(to, zone, "to"))
                : repo.findByOrganizationIdOrderByStartTimeAsc(principal.orgId());
        return events.stream().map(EventController::view).toList();
    }

    @PostMapping({"/api/events", "/api/v1/events"})
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.CALENDAR_WRITE)
    public EventView create(AuthPrincipal principal, @Valid @RequestBody EventInput in) {
        CalendarEvent e = new CalendarEvent();
        e.setOrganizationId(principal.orgId());
        e.setUserId(principal.userId());
        e.setCreatedByUserId(principal.userId());
        apply(e, in, ZoneId.of(settings.timezone(principal.userId())));
        CalendarEvent saved = repo.save(e);
        activity.log(principal.orgId(), principal.userId(), "EVENT", "Event added: " + saved.getTitle());
        return view(saved);
    }

    @PutMapping({"/api/events/{id}", "/api/v1/events/{id}"})
    @RequiresPermission(Permission.CALENDAR_WRITE)
    public EventView update(AuthPrincipal principal, @PathVariable Long id, @Valid @RequestBody EventInput in) {
        CalendarEvent e = manual(principal, id);
        apply(e, in, ZoneId.of(settings.timezone(principal.userId())));
        return view(repo.save(e));
    }

    @DeleteMapping({"/api/events/{id}", "/api/v1/events/{id}"})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.CALENDAR_WRITE)
    public void delete(AuthPrincipal principal, @PathVariable Long id) {
        repo.delete(manual(principal, id));
    }

    private CalendarEvent manual(AuthPrincipal principal, Long id) {
        CalendarEvent e = repo.findByIdAndOrganizationId(id, principal.orgId()).orElseThrow(() -> ApiException.notFound("Event"));
        if (!"MANUAL".equals(e.getSource())) {
            throw ApiException.conflict("Pitch meetings are managed from their workflow (reschedule or cancel there).");
        }
        return e;
    }

    private static void apply(CalendarEvent e, EventInput in, ZoneId zone) {
        if (in.title() == null || in.title().isBlank()) {
            throw ApiException.badRequest("Title is required.");
        }
        Instant start = parseInstant(in.startTime(), zone, "startTime");
        Instant end = parseInstant(in.endTime(), zone, "endTime");
        if (!end.isAfter(start)) {
            throw ApiException.badRequest("End must be after the start.");
        }
        e.setTitle(in.title().trim());
        e.setDescription(in.description());
        e.setLocation(in.location());
        e.setStartTime(start);
        e.setEndTime(end);
    }

    public static EventView view(CalendarEvent e) {
        return new EventView(e.getId(), e.getTitle(), e.getDescription(), e.getLocation(), e.getStartTime(), e.getEndTime(),
                e.getSource(), e.getProvider(), e.getSyncStatus(), e.getConferenceLink(), e.getHtmlLink(), e.getPitchId(),
                e.getWorkflowId(), e.getUserId());
    }

    /** Accepts "2026-10-05T08:30:00.000Z", "2026-10-05T14:00:00+05:30" or a local "2026-10-05T14:00". */
    public static Instant parseInstant(String value, ZoneId zone, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " is required.");
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // try local date-time next
        }
        try {
            return LocalDateTime.parse(value).atZone(zone).toInstant();
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(field + " must be an ISO date-time");
        }
    }
}
