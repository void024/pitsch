package com.pitsch.backend.workflow;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.pitsch.backend.activity.ActivityService;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.email.Email;
import com.pitsch.backend.email.EmailRepository;
import com.pitsch.backend.event.CalendarEvent;
import com.pitsch.backend.event.CalendarEventRepository;
import com.pitsch.backend.pitch.Pitch;
import com.pitsch.backend.pitch.PitchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Executes the payloads produced by the Action Agent.
 *
 * Integration mode "simulated" (the only mode for now): Gmail labels are stored on the email, pipeline rows
 * update the pitch, approved meetings become Pitsch calendar events, and sent emails are recorded in the
 * activity log. To connect real Google APIs, implement the same four cases with the Gmail / Calendar /
 * Sheets clients — the payloads are already in Google API format.
 *
 * Safety rules enforced here (from the Action Agent contract):
 *  - never execute an action with requiresApproval && !approved
 *  - run actions in order, and only after everything in dependsOn succeeded
 *  - skip action IDs already executed for this workflow (idempotent retries)
 */
@Component
public class ActionExecutor {

    public record Report(List<String> executed, List<String> skipped, Long createdEventId) { }

    private static final Logger log = LoggerFactory.getLogger(ActionExecutor.class);

    private final EmailRepository emails;
    private final PitchRepository pitches;
    private final CalendarEventRepository events;
    private final ActivityService activity;

    public ActionExecutor(EmailRepository emails, PitchRepository pitches, CalendarEventRepository events,
                          ActivityService activity) {
        this.emails = emails;
        this.pitches = pitches;
        this.events = events;
        this.activity = activity;
    }

    public Report execute(Workflow wf, JsonNode actionData) {
        Set<String> done = new LinkedHashSet<>(Json.splitCsv(wf.getExecutedActionIds()));
        Set<String> succeededNow = new HashSet<>();
        List<String> executed = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Long createdEventId = null;

        for (JsonNode action : actionData.path("actions")) {
            String id = Json.text(action, "actionId");
            String type = Json.text(action, "type");
            if (id == null || type == null) {
                continue;
            }
            if (done.contains(id)) {
                succeededNow.add(id);
                continue;
            }
            if (action.path("requiresApproval").asBoolean(false) && !action.path("approved").asBoolean(false)) {
                skipped.add(type + " (needs approval)");
                continue;
            }
            boolean depsOk = true;
            for (String dep : Json.strings(action, "dependsOn")) {
                if (!succeededNow.contains(dep) && !done.contains(dep)) {
                    depsOk = false;
                }
            }
            if (!depsOk) {
                skipped.add(type + " (dependency failed)");
                continue;
            }
            try {
                JsonNode payload = action.path("payload");
                switch (type) {
                    case "GMAIL_ADD_LABELS" -> labels(wf, Json.strings(payload, "labels"), true);
                    case "GMAIL_REMOVE_LABELS" -> labels(wf, Json.strings(payload, "labels"), false);
                    case "SHEETS_UPSERT_ROW" -> pipelineRow(wf, payload.path("values"));
                    case "CALENDAR_CREATE_EVENT" -> createdEventId = calendarEvent(wf, payload.path("event"));
                    case "GMAIL_SEND_EMAIL" -> activity.log(wf.getUserId(), "WORKFLOW",
                            "Email sent to " + Json.text(payload, "to") + " — \"" + Json.text(payload, "subject")
                                    + "\" (simulated: connect Gmail to send for real)");
                    default -> {
                        skipped.add(type + " (unknown action type)");
                        continue;
                    }
                }
                done.add(id);
                succeededNow.add(id);
                executed.add(type);
            } catch (RuntimeException e) {
                log.warn("Action {} failed for workflow {}", type, wf.getId(), e);
                skipped.add(type + " (failed: " + e.getMessage() + ")");
            }
        }
        wf.setExecutedActionIds(Json.joinCsv(new ArrayList<>(done)));
        return new Report(executed, skipped, createdEventId);
    }

    private void labels(Workflow wf, List<String> labels, boolean add) {
        if (wf.getEmailId() == null) {
            return;
        }
        emails.findById(wf.getEmailId()).ifPresent((Email e) -> {
            Set<String> current = new LinkedHashSet<>(Json.splitCsv(e.getLabels()));
            if (add) {
                current.addAll(labels);
            } else {
                labels.forEach(current::remove);
            }
            e.setLabels(Json.joinCsv(new ArrayList<>(current)));
            emails.save(e);
        });
    }

    private void pipelineRow(Workflow wf, JsonNode values) {
        if (wf.getPitchId() == null) {
            return;
        }
        pitches.findById(wf.getPitchId()).ifPresent((Pitch p) -> {
            String status = Json.text(values, "Status");
            if (status != null) {
                p.setStatus(status);
            }
            pitches.save(p);
            activity.log(wf.getUserId(), "WORKFLOW",
                    "Pipeline updated: " + p.getCompanyName() + " → " + p.getStatus());
        });
    }

    private Long calendarEvent(Workflow wf, JsonNode event) {
        CalendarEvent e = new CalendarEvent();
        e.setUserId(wf.getUserId());
        e.setTitle(Json.truncate(firstNonNull(Json.text(event, "summary"), "Pitch meeting"), 250));
        e.setDescription(Json.text(event, "description"));
        e.setLocation(event.has("conferenceData") ? "Video call (link added once Google Calendar is connected)" : null);
        e.setStartTime(parse(event.path("start").path("dateTime").asText()));
        e.setEndTime(parse(event.path("end").path("dateTime").asText()));
        e.setPitchId(wf.getPitchId());
        e.setWorkflowId(wf.getId());
        e.setSource("PITSCH");
        CalendarEvent saved = events.save(e);
        activity.log(wf.getUserId(), "EVENT", "Meeting scheduled: " + saved.getTitle()
                + " (added to the Pitsch calendar; Google Calendar invite is simulated)");
        return saved.getId();
    }

    private static java.time.Instant parse(String iso) {
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Bad event time: " + iso);
        }
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
