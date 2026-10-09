package com.pitsch.backend.workflow;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pitsch.backend.auth.User;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.email.Email;
import com.pitsch.backend.email.EmailAttachment;
import com.pitsch.backend.files.FileService;
import com.pitsch.backend.files.StoredFileRepository;
import com.pitsch.backend.org.Organization;
import com.pitsch.backend.pitch.Pitch;
import com.pitsch.backend.user.UserSettings;
import org.springframework.stereotype.Component;

/**
 * Builds each agent's input exactly as the AI service schemas expect (camelCase, see
 * ai-service/docs/integration.md and docs/examples). Earlier agents' outputs are passed through verbatim.
 */
@Component
public class AgentInputs {

    /** A busy interval in the investor's calendar (Google free/busy or Pitsch events). */
    public record BusyInterval(Instant start, Instant end) { }

    private static final int MAX_DOCUMENTS = 10;
    private final Json json;
    private final FileService files;
    private final StoredFileRepository storedFiles;

    public AgentInputs(Json json, FileService files, StoredFileRepository storedFiles) {
        this.json = json;
        this.files = files;
        this.storedFiles = storedFiles;
    }

    // ---------------- 1. Email Classifier ----------------

    public ObjectNode classifier(Email email, List<EmailAttachment> attachments, List<Pitch> candidates) {
        ObjectNode in = json.obj();
        in.put("emailId", String.valueOf(email.getId()));
        putIfText(in, "threadId", email.getThreadId());
        putIfText(in, "inReplyTo", email.getInReplyTo());
        ObjectNode sender = in.putObject("sender");
        sender.put("email", email.getSender());
        putIfText(sender, "name", email.getSenderName());
        ArrayNode to = in.putArray("to");
        Json.splitCsv(email.getRecipients()).forEach(to::add);
        in.put("subject", nz(email.getSubject()));
        in.put("body", nz(email.getBody()));
        in.put("receivedAt", email.getReceivedAt().toString());
        ArrayNode atts = in.putArray("attachments");
        for (EmailAttachment a : attachments) {
            ObjectNode n = atts.addObject();
            n.put("filename", a.getFilename());
            n.put("mimeType", a.getMimeType());
            n.put("sizeBytes", a.getSizeBytes());
            n.put("readable", a.getStoredFileId() != null || (a.getContentBase64() != null && !a.getContentBase64().isBlank()));
        }
        ArrayNode cands = in.putArray("candidatePitches");
        for (Pitch p : candidates.subList(0, Math.min(20, candidates.size()))) {
            ObjectNode c = cands.addObject();
            c.put("pitchId", String.valueOf(p.getId()));
            c.put("companyName", p.getCompanyName() != null ? p.getCompanyName() : "Unknown company");
            putIfText(c, "companyDomain", p.getCompanyDomain());
            ArrayNode founders = c.putArray("founderEmails");
            if (p.getFounderEmail() != null) {
                founders.add(p.getFounderEmail());
            }
            ArrayNode threads = c.putArray("threadIds");
            Json.splitCsv(p.getThreadIds()).forEach(threads::add);
            if (p.getUpdatedAt() != null) {
                c.put("lastActivityAt", p.getUpdatedAt().toString());
            }
        }
        return in;
    }

    // ---------------- 2. Document Agent ----------------

    public ObjectNode document(Pitch pitch, Email email, List<EmailAttachment> attachments) {
        ObjectNode in = json.obj();
        in.put("pitchId", String.valueOf(pitch.getId()));
        putIfText(in, "companyNameHint", pitch.getCompanyName());
        in.put("senderEmail", email.getSender());
        in.put("emailSubject", nz(email.getSubject()));
        in.put("emailBody", nz(email.getBody()));
        ArrayNode docs = in.putArray("documents");
        for (EmailAttachment a : attachments) {
            if (docs.size() >= MAX_DOCUMENTS) {
                break;
            }
            String content = content(a);
            if (content == null) {
                continue;
            }
            ObjectNode d = docs.addObject();
            d.put("documentId", "att_" + a.getId());
            d.put("filename", a.getFilename());
            d.put("mimeType", a.getMimeType());
            d.put("contentBase64", content);
        }
        return in;
    }

    // ---------------- 3. Research Agent ----------------

    public ObjectNode research(Pitch pitch, JsonNode doc) {
        JsonNode company = doc.path("company");
        ObjectNode in = json.obj();
        in.put("pitchId", String.valueOf(pitch.getId()));
        String name = firstText(Json.text(company, "name"), pitch.getCompanyName(), "Unknown company");
        in.put("companyName", Json.truncate(name, 200));
        putIfText(in, "companyDomain", firstText(pitch.getCompanyDomain(), Json.domainOf(Json.text(company, "website"))));
        ArrayNode founders = in.putArray("founders");
        for (JsonNode f : doc.path("founders")) {
            if (founders.size() < 10 && Json.text(f, "name") != null) {
                founders.add(Json.text(f, "name"));
            }
        }
        putIfText(in, "sector", Json.text(company, "sector"));
        putIfText(in, "location", Json.text(company, "location"));
        putIfText(in, "oneLiner", Json.text(company, "oneLiner"));
        in.set("claims", limit(doc.path("claims"), 40));
        return in;
    }

    // ---------------- 4. Verification Agent ----------------

    public ObjectNode verification(Pitch pitch, JsonNode doc, JsonNode research) {
        ObjectNode in = json.obj();
        in.put("pitchId", String.valueOf(pitch.getId()));
        in.put("companyName", firstText(Json.text(doc.path("company"), "name"), pitch.getCompanyName(), "Unknown company"));
        in.set("claims", limit(doc.path("claims"), 60));
        in.set("evidence", limit(research.path("evidence"), 300));
        return in;
    }

    // ---------------- 5. Analysis Agent ----------------

    public ObjectNode analysis(Pitch pitch, JsonNode doc, JsonNode research, JsonNode verification) {
        ObjectNode in = json.obj();
        in.put("pitchId", String.valueOf(pitch.getId()));
        in.set("document", doc);
        in.set("research", research == null ? NullNode.getInstance() : research);
        in.set("verification", verification == null ? NullNode.getInstance() : verification);
        return in;
    }

    // ---------------- 6. Calendar Agent ----------------

    public ObjectNode calendar(Pitch pitch, Email email, JsonNode classification, String timezone, Organization org,
                               List<BusyInterval> busy, Integer durationMinutes) {
        ObjectNode in = json.obj();
        in.put("pitchId", String.valueOf(pitch.getId()));
        in.put("timezone", timezone);
        in.put("durationMinutes", Math.max(15, Math.min(240,
                durationMinutes != null ? durationMinutes : org.getMeetingDurationMinutes())));
        ObjectNode hours = in.putObject("workingHours");
        hours.put("start", org.getWorkingHoursStart());
        hours.put("end", org.getWorkingHoursEnd());
        ArrayNode days = hours.putArray("days");
        for (String d : org.getWorkingDays().split(",")) {
            days.add(d.trim());
        }
        ArrayNode b = in.putArray("busy");
        for (BusyInterval e : busy.subList(0, Math.min(500, busy.size()))) {
            if (e.end().isAfter(e.start())) {
                ObjectNode n = b.addObject();
                n.put("start", e.start().toString());
                n.put("end", e.end().toString());
            }
        }
        if (Json.bool(classification, "meetingRequested") && email.getBody() != null && !email.getBody().isBlank()) {
            in.put("founderAvailabilityText", Json.truncate(email.getBody(), 4000));
            in.put("referenceTime", email.getReceivedAt().toString());
        }
        in.put("maxSuggestions", 3);
        return in;
    }

    // ---------------- 7. Email Response Agent ----------------

    public ObjectNode emailResponse(String purpose, Pitch pitch, Email email, User user, UserSettings settings,
                                    String timezone, List<String> questions, JsonNode slots,
                                    Instant meetingStart, Instant meetingEnd, String instructions) {
        ObjectNode in = json.obj();
        in.put("pitchId", String.valueOf(pitch.getId()));
        in.put("purpose", purpose);
        ObjectNode recipient = in.putObject("recipient");
        recipient.put("email", firstText(pitch.getFounderEmail(), email.getSender()));
        putIfText(recipient, "name", firstText(pitch.getFounderName(), email.getSenderName()));
        ObjectNode investor = in.putObject("investor");
        investor.put("name", user.getName());
        putIfText(investor, "title", settings.getInvestorTitle());
        putIfText(investor, "firm", settings.getFirmName());
        investor.put("email", user.getEmail());
        putIfText(in, "companyName", pitch.getCompanyName());
        ObjectNode thread = in.putObject("thread");
        thread.put("subject", nz(email.getSubject()));
        thread.put("body", Json.truncate(nz(email.getBody()), 6000));
        ArrayNode qs = in.putArray("questions");
        questions.stream().limit(10).forEach(qs::add);
        ArrayNode proposed = in.putArray("proposedSlots");
        if (slots != null) {
            for (JsonNode s : slots) {
                if (proposed.size() < 6) {
                    ObjectNode n = proposed.addObject();
                    n.put("start", s.path("start").asText());
                    n.put("end", s.path("end").asText());
                }
            }
        }
        if (meetingStart != null && meetingEnd != null) {
            ObjectNode m = in.putObject("meeting");
            m.put("start", iso(meetingStart, ZoneId.of(timezone)));
            m.put("end", iso(meetingEnd, ZoneId.of(timezone)));
        }
        in.put("timezone", timezone);
        putIfText(in, "instructions", Json.truncate(instructions, 2000));
        in.put("tone", "WARM");
        return in;
    }

    // ---------------- 8. Action Agent ----------------

    public ObjectNode action(String event, Workflow wf, Email email, Pitch pitch, String briefUrl, JsonNode analysis) {
        ObjectNode in = json.obj();
        in.put("workflowId", String.valueOf(wf.getId()));
        in.put("event", event);
        if (email != null) {
            in.put("emailId", email.getGmailId() != null ? email.getGmailId() : "pitsch-email-" + email.getId());
            putIfText(in, "threadId", email.getThreadId());
        }
        if (pitch != null) {
            ObjectNode p = in.putObject("pitch");
            p.put("pitchId", String.valueOf(pitch.getId()));
            putIfText(p, "companyName", pitch.getCompanyName());
            putIfText(p, "sector", pitch.getSector());
            putIfText(p, "stage", pitch.getStage());
            putIfText(p, "founderName", pitch.getFounderName());
            putIfText(p, "founderEmail", pitch.getFounderEmail());
            putIfText(p, "amountRequested", pitch.getAmountRequested());
            putIfText(p, "website", pitch.getWebsite());
            putIfText(p, "briefUrl", briefUrl);
            if (analysis != null) {
                JsonNode summary = analysis.path("claimStatusSummary");
                if (summary.isObject()) {
                    p.set("claimStatusSummary", summary);
                }
                JsonNode questions = analysis.path("brief").path("openQuestions");
                if (questions.isArray()) {
                    p.put("openQuestionCount", questions.size());
                }
            }
        }
        return in;
    }

    public void addApproval(ObjectNode actionInput, User user) {
        ObjectNode approval = actionInput.putObject("approval");
        approval.put("approvedBy", user.getEmail());
        approval.put("approvedAt", Instant.now().toString());
    }

    // ---------------- helpers ----------------

    /** Attachment bytes as base64 from object storage (or the legacy in-database copy). */
    private String content(EmailAttachment a) {
        if (a.getStoredFileId() != null) {
            return storedFiles.findById(a.getStoredFileId())
                    .map(f -> java.util.Base64.getEncoder().encodeToString(files.read(f)))
                    .orElse(null);
        }
        return a.getContentBase64() == null || a.getContentBase64().isBlank() ? null : a.getContentBase64();
    }

    private static final DateTimeFormatter ISO_WITH_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    /** "2026-10-07T14:00:00+05:30" — always with seconds and offset. */
    static String iso(Instant instant, ZoneId zone) {
        return instant.atZone(zone).format(ISO_WITH_SECONDS);
    }

    private ArrayNode limit(JsonNode array, int max) {
        ArrayNode out = json.arr();
        if (array != null && array.isArray()) {
            for (JsonNode n : array) {
                if (out.size() >= max) {
                    break;
                }
                out.add(n);
            }
        }
        return out;
    }

    private static void putIfText(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    static String firstText(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /** Lower-case helper for matching names in text. */
    static boolean mentions(String text, String name) {
        if (text == null || name == null || name.trim().length() < 3) {
            return false;
        }
        return text.toLowerCase(Locale.ROOT).contains(name.trim().toLowerCase(Locale.ROOT));
    }
}
