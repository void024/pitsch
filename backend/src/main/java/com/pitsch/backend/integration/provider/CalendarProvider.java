package com.pitsch.backend.integration.provider;

import java.time.Instant;
import java.util.List;

import com.pitsch.backend.integration.IntegrationConnection;

/** The investor's calendar. The Calendar Agent only proposes slots; only the backend calls create/update/cancel. */
public interface CalendarProvider {

    record Busy(Instant start, Instant end) { }

    record CalendarInfo(String id, String summary, boolean primary, String timeZone) { }

    record EventRequest(String title, String description, Instant start, Instant end, String timeZone,
                        List<String> attendeeEmails, boolean addVideoConference, List<Integer> reminderMinutes) { }

    record EventState(String eventId, boolean exists, boolean cancelled, Instant start, Instant end, String htmlLink,
                      String conferenceLink) { }

    String name();

    boolean isDemo();

    List<CalendarInfo> calendars(IntegrationConnection connection);

    List<Busy> freeBusy(IntegrationConnection connection, String calendarId, Instant from, Instant to);

    /** Idempotent: the same key always maps to the same external event ID. */
    EventState create(IntegrationConnection connection, String calendarId, EventRequest request, String idempotencyKey);

    EventState update(IntegrationConnection connection, String calendarId, String eventId, EventRequest request);

    void cancel(IntegrationConnection connection, String calendarId, String eventId);

    EventState get(IntegrationConnection connection, String calendarId, String eventId);
}
