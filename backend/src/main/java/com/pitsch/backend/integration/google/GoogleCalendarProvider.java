package com.pitsch.backend.integration.google;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pitsch.backend.common.Hashing;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.integration.IntegrationConnection;
import com.pitsch.backend.integration.IntegrationException;
import com.pitsch.backend.integration.provider.CalendarProvider;
import org.springframework.stereotype.Component;

/** Google Calendar API v3 implementation. Event IDs are derived from idempotency keys, so retries never duplicate. */
@Component
public class GoogleCalendarProvider implements CalendarProvider {

    static final String API = "https://www.googleapis.com/calendar/v3";
    private static final char[] BASE32HEX = "0123456789abcdefghijklmnopqrstuv".toCharArray();

    private final GoogleApiClient google;
    private final Json json;

    public GoogleCalendarProvider(GoogleApiClient google, Json json) {
        this.google = google;
        this.json = json;
    }

    @Override
    public String name() {
        return "google-calendar";
    }

    @Override
    public boolean isDemo() {
        return false;
    }

    @Override
    public List<CalendarInfo> calendars(IntegrationConnection c) {
        List<CalendarInfo> out = new ArrayList<>();
        google.get(c, "calendar.list", API + "/users/me/calendarList?minAccessRole=writer").path("items").forEach(i ->
                out.add(new CalendarInfo(i.path("id").asText(), i.path("summary").asText(),
                        i.path("primary").asBoolean(false), Json.text(i, "timeZone"))));
        return out;
    }

    @Override
    public List<Busy> freeBusy(IntegrationConnection c, String calendarId, Instant from, Instant to) {
        String cal = calendarId == null || calendarId.isBlank() ? "primary" : calendarId;
        ObjectNode body = json.obj();
        body.put("timeMin", from.toString());
        body.put("timeMax", to.toString());
        body.putArray("items").addObject().put("id", cal);
        JsonNode res = google.post(c, "calendar.freebusy", API + "/freeBusy", body);
        JsonNode calendars = res.path("calendars");
        JsonNode entry = calendars.has(cal) ? calendars.path(cal) : calendars.elements().hasNext() ? calendars.elements().next() : null;
        List<Busy> out = new ArrayList<>();
        if (entry != null) {
            if (entry.path("errors").size() > 0) {
                throw new IntegrationException("Google could not read calendar availability", true, false, 0);
            }
            entry.path("busy").forEach(b -> out.add(new Busy(Instant.parse(b.path("start").asText()), Instant.parse(b.path("end").asText()))));
        }
        return out;
    }

    @Override
    public EventState create(IntegrationConnection c, String calendarId, EventRequest req, String idempotencyKey) {
        String cal = calendarId == null || calendarId.isBlank() ? "primary" : calendarId;
        String eventId = eventId(idempotencyKey);
        ObjectNode body = eventBody(req, eventId);
        try {
            JsonNode created = google.post(c, "calendar.insert",
                    API + "/calendars/{cal}/events?conferenceDataVersion={v}&sendUpdates=all", body, cal, req.addVideoConference() ? 1 : 0);
            return state(created);
        } catch (IntegrationException e) {
            if (e.getProviderStatus() == 409) {
                return get(c, cal, eventId);   // created by an earlier attempt
            }
            throw e;
        }
    }

    @Override
    public EventState update(IntegrationConnection c, String calendarId, String eventId, EventRequest req) {
        String cal = calendarId == null || calendarId.isBlank() ? "primary" : calendarId;
        JsonNode updated = google.patch(c, "calendar.patch", API + "/calendars/{cal}/events/{id}?sendUpdates=all",
                eventBody(req, null), cal, eventId);
        return state(updated);
    }

    @Override
    public void cancel(IntegrationConnection c, String calendarId, String eventId) {
        String cal = calendarId == null || calendarId.isBlank() ? "primary" : calendarId;
        try {
            google.delete(c, "calendar.delete", API + "/calendars/{cal}/events/{id}?sendUpdates=all", cal, eventId);
        } catch (IntegrationException e) {
            if (e.getProviderStatus() != 404 && e.getProviderStatus() != 410) {
                throw e;
            }
        }
    }

    @Override
    public EventState get(IntegrationConnection c, String calendarId, String eventId) {
        String cal = calendarId == null || calendarId.isBlank() ? "primary" : calendarId;
        try {
            return state(google.get(c, "calendar.get", API + "/calendars/{cal}/events/{id}", cal, eventId));
        } catch (IntegrationException e) {
            if (e.getProviderStatus() == 404 || e.getProviderStatus() == 410) {
                return new EventState(eventId, false, true, null, null, null, null);
            }
            throw e;
        }
    }

    private ObjectNode eventBody(EventRequest req, String eventId) {
        ObjectNode body = json.obj();
        if (eventId != null) {
            body.put("id", eventId);
        }
        body.put("summary", req.title());
        if (req.description() != null) {
            body.put("description", req.description());
        }
        body.putObject("start").put("dateTime", req.start().toString()).put("timeZone", req.timeZone());
        body.putObject("end").put("dateTime", req.end().toString()).put("timeZone", req.timeZone());
        ArrayNode attendees = body.putArray("attendees");
        for (String a : req.attendeeEmails()) {
            attendees.addObject().put("email", a);
        }
        ObjectNode reminders = body.putObject("reminders");
        if (req.reminderMinutes() == null || req.reminderMinutes().isEmpty()) {
            reminders.put("useDefault", true);
        } else {
            reminders.put("useDefault", false);
            ArrayNode overrides = reminders.putArray("overrides");
            for (Integer m : req.reminderMinutes()) {
                overrides.addObject().put("method", "popup").put("minutes", m);
            }
        }
        if (req.addVideoConference() && eventId != null) {
            body.putObject("conferenceData").putObject("createRequest").put("requestId", eventId)
                    .putObject("conferenceSolutionKey").put("type", "hangoutsMeet");
        }
        return body;
    }

    private static EventState state(JsonNode e) {
        String link = Json.text(e, "hangoutLink");
        if (link == null) {
            for (JsonNode ep : e.path("conferenceData").path("entryPoints")) {
                if ("video".equals(ep.path("entryPointType").asText())) {
                    link = Json.text(ep, "uri");
                }
            }
        }
        return new EventState(Json.text(e, "id"), true, "cancelled".equals(Json.text(e, "status")),
                parse(e.path("start").path("dateTime").asText(null)), parse(e.path("end").path("dateTime").asText(null)),
                Json.text(e, "htmlLink"), link);
    }

    private static Instant parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Google event IDs: 5–1024 chars of base32hex (a–v, 0–9). 26 chars = 130 bits of the key's SHA-256. */
    static String eventId(String idempotencyKey) {
        byte[] digest = Hashing.sha256Hex(idempotencyKey).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        StringBuilder sb = new StringBuilder("p");
        int buffer = 0;
        int bits = 0;
        for (byte b : digest) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5 && sb.length() < 27) {
                sb.append(BASE32HEX[(buffer >> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        return sb.toString();
    }
}
