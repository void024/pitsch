package com.pitsch.backend.event;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;

import com.pitsch.backend.activity.ActivityService;
import com.pitsch.backend.auth.AuthInterceptor;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.user.SettingsService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
public class EventController {

    public record EventInput(String title, String description, String location, String startTime, String endTime) { }

    private final CalendarEventRepository repo;
    private final ActivityService activity;
    private final SettingsService settings;

    public EventController(CalendarEventRepository repo, ActivityService activity, SettingsService settings) {
        this.repo = repo;
        this.activity = activity;
        this.settings = settings;
    }

    @GetMapping
    public List<CalendarEvent> list(@RequestAttribute(AuthInterceptor.USER_ID) Long userId) {
        return repo.findByUserIdOrderByStartTimeAsc(userId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CalendarEvent create(@RequestAttribute(AuthInterceptor.USER_ID) Long userId, @RequestBody EventInput in) {
        CalendarEvent e = new CalendarEvent();
        e.setUserId(userId);
        apply(e, in, ZoneId.of(settings.timezone(userId)));
        CalendarEvent saved = repo.save(e);
        activity.log(userId, "EVENT", "Event added: " + saved.getTitle());
        return saved;
    }

    @PutMapping("/{id}")
    public CalendarEvent update(@RequestAttribute(AuthInterceptor.USER_ID) Long userId, @PathVariable Long id,
                                @RequestBody EventInput in) {
        CalendarEvent e = repo.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Event"));
        apply(e, in, ZoneId.of(settings.timezone(userId)));
        return repo.save(e);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestAttribute(AuthInterceptor.USER_ID) Long userId, @PathVariable Long id) {
        repo.delete(repo.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Event")));
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
