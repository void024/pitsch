package com.pitsch.backend.integration.mock;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.pitsch.backend.common.Hashing;
import com.pitsch.backend.integration.IntegrationConnection;
import com.pitsch.backend.integration.provider.CalendarProvider;

/** PITSCH_MODE=demo only: events live in memory (and as labelled Pitsch calendar entries); no invite is sent. */
public class MockCalendarProvider implements CalendarProvider {

    private final Map<String, EventState> events = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "demo-calendar";
    }

    @Override
    public boolean isDemo() {
        return true;
    }

    @Override
    public List<CalendarInfo> calendars(IntegrationConnection connection) {
        return List.of(new CalendarInfo("primary", "Demo calendar", true, "UTC"));
    }

    @Override
    public List<Busy> freeBusy(IntegrationConnection connection, String calendarId, Instant from, Instant to) {
        return events.values().stream().filter(e -> !e.cancelled() && e.start() != null
                && e.start().isBefore(to) && e.end().isAfter(from)).map(e -> new Busy(e.start(), e.end())).toList();
    }

    @Override
    public EventState create(IntegrationConnection connection, String calendarId, EventRequest req, String idempotencyKey) {
        String id = "demo-" + Hashing.sha256Hex(idempotencyKey).substring(0, 20);
        return events.computeIfAbsent(id, k -> new EventState(id, true, false, req.start(), req.end(), null, null));
    }

    @Override
    public EventState update(IntegrationConnection connection, String calendarId, String eventId, EventRequest req) {
        EventState updated = new EventState(eventId, true, false, req.start(), req.end(), null, null);
        events.put(eventId, updated);
        return updated;
    }

    @Override
    public void cancel(IntegrationConnection connection, String calendarId, String eventId) {
        events.computeIfPresent(eventId, (k, e) -> new EventState(k, true, true, e.start(), e.end(), null, null));
    }

    @Override
    public EventState get(IntegrationConnection connection, String calendarId, String eventId) {
        return events.getOrDefault(eventId, new EventState(eventId, false, true, null, null, null, null));
    }
}
