package com.pitsch.backend.workflow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.pitsch.backend.ai.Agent;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.email.Email;
import com.pitsch.backend.email.EmailAttachment;
import com.pitsch.backend.email.EmailAttachmentRepository;
import com.pitsch.backend.email.EmailRepository;
import com.pitsch.backend.event.CalendarEvent;
import com.pitsch.backend.event.CalendarEventRepository;
import com.pitsch.backend.pitch.Pitch;
import com.pitsch.backend.pitch.PitchRepository;
import org.springframework.stereotype.Component;

/** JSON shapes returned to the frontend. List views are assembled with batched lookups (no N+1 queries). */
@Component
public class WorkflowViews {

    public record AgentStatusView(String agent, String label, String status, String errorMessage,
                                  Instant startedAt, Instant completedAt, long latencyMs,
                                  long promptTokens, long completionTokens, String model, java.math.BigDecimal estimatedCostUsd,
                                  int attempts) { }

    public record AttachmentView(Long id, String filename, String mimeType, long sizeBytes) { }

    public record EmailView(Long id, String sender, String senderName, String subject, String body,
                            Instant receivedAt, String threadId, List<AttachmentView> attachments,
                            List<String> labels, Boolean isPitch, Boolean isFollowUp, String category, String source) { }

    public record DraftView(Long id, String recipient, String recipientName, String subject, String body,
                            String purpose, String status, boolean needsHumanReview, List<String> reviewReasons,
                            Instant createdAt, Instant sentAt, String failureReason) { }

    public record MeetingView(Instant start, Instant end, Long eventId, String syncStatus, String conferenceLink,
                              String htmlLink, String provider) { }

    /**
     * Workflow as returned by list (summary fields only) and detail (everything). Field names follow the original
     * frontend/backend contract, extended with tenancy-neutral fields.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkflowView(Long id, Long workflowId, String type, String status, String currentStep,
                               String recommendedAction, List<String> availableActions, String prompt,
                               String result, String error, Instant createdAt, Instant updatedAt,
                               Instant completedAt, Long pitchId, String companyName, Long emailId, String sender,
                               String senderName, Double confidence, String classificationReason,
                               boolean needsHumanReview, List<String> reviewReasons, List<String> warnings,
                               List<AgentStatusView> agents, EmailView email, JsonNode brief, String briefMarkdown,
                               JsonNode slots, String slotsTimezone, MeetingView meeting, DraftView draft,
                               Long ownerUserId, String emailSource, Boolean promptInjectionSuspected) { }

    private static final List<Agent> PIPELINE = List.of(Agent.DOCUMENT_AGENT, Agent.RESEARCH_AGENT,
            Agent.VERIFICATION_AGENT, Agent.ANALYSIS_AGENT);

    private final AgentExecutionRepository executions;
    private final EmailRepository emails;
    private final EmailAttachmentRepository attachments;
    private final PitchRepository pitches;
    private final EmailDraftRepository drafts;
    private final CalendarEventRepository events;
    private final Json json;

    public WorkflowViews(AgentExecutionRepository executions, EmailRepository emails,
                         EmailAttachmentRepository attachments, PitchRepository pitches,
                         EmailDraftRepository drafts, CalendarEventRepository events, Json json) {
        this.executions = executions;
        this.emails = emails;
        this.attachments = attachments;
        this.pitches = pitches;
        this.drafts = drafts;
        this.events = events;
        this.json = json;
    }

    public List<WorkflowView> summaries(Collection<Workflow> list) {
        Map<Long, Email> emailById = emails.findAllById(list.stream().map(Workflow::getEmailId).filter(Objects::nonNull)
                .distinct().toList()).stream().collect(Collectors.toMap(Email::getId, Function.identity()));
        Map<Long, Pitch> pitchById = pitches.findAllById(list.stream().map(Workflow::getPitchId).filter(Objects::nonNull)
                .distinct().toList()).stream().collect(Collectors.toMap(Pitch::getId, Function.identity()));
        List<WorkflowView> out = new ArrayList<>();
        for (Workflow wf : list) {
            out.add(build(wf, false, emailById.get(wf.getEmailId()), pitchById.get(wf.getPitchId())));
        }
        return out;
    }

    public WorkflowView summary(Workflow wf) {
        return summaries(List.of(wf)).get(0);
    }

    public WorkflowView detail(Workflow wf) {
        Email email = wf.getEmailId() == null ? null : emails.findById(wf.getEmailId()).orElse(null);
        Pitch pitch = wf.getPitchId() == null ? null : pitches.findById(wf.getPitchId()).orElse(null);
        return build(wf, true, email, pitch);
    }

    private WorkflowView build(Workflow wf, boolean detail, Email email, Pitch pitch) {
        JsonNode classification = json.read(wf.getClassificationJson());
        JsonNode analysis = detail ? json.read(wf.getAnalysisJson()) : null;
        JsonNode calendar = detail ? json.read(wf.getCalendarJson()) : null;
        MeetingView meeting = null;
        if (wf.getMeetingStart() != null) {
            CalendarEvent e = detail && wf.getMeetingEventId() != null ? events.findById(wf.getMeetingEventId()).orElse(null) : null;
            meeting = new MeetingView(wf.getMeetingStart(), wf.getMeetingEnd(), wf.getMeetingEventId(),
                    e == null ? null : e.getSyncStatus(), e == null ? null : e.getConferenceLink(),
                    e == null ? null : e.getHtmlLink(), e == null ? null : e.getProvider());
        }
        return new WorkflowView(
                wf.getId(), wf.getId(), wf.getType(), wf.getStatus(), wf.getCurrentStep(), wf.getRecommendedAction(),
                Json.splitCsv(wf.getAvailableActions()), wf.getSummary(),
                detail ? result(analysis, classification) : Json.text(classification, "reason"), wf.getError(),
                wf.getCreatedAt(), wf.getUpdatedAt(), wf.getCompletedAt(), wf.getPitchId(),
                pitch == null ? null : pitch.getCompanyName(), wf.getEmailId(),
                email == null ? null : email.getSender(), email == null ? null : email.getSenderName(),
                classification == null || !classification.has("confidence") ? null : classification.path("confidence").asDouble(),
                Json.text(classification, "reason"), wf.isNeedsHumanReview(), list(wf.getReviewReasonsJson()),
                list(wf.getWarningsJson()),
                detail ? agents(wf) : null,
                detail && email != null ? emailView(email) : null,
                detail && analysis != null ? analysis.get("brief") : null,
                detail ? wf.getBriefMarkdown() : null,
                calendar == null ? null : calendar.get("slots"),
                calendar == null ? null : Json.text(calendar, "timezone"),
                meeting,
                detail ? drafts.findFirstByWorkflowIdOrderByIdDesc(wf.getId()).map(this::draftView).orElse(null) : null,
                wf.getUserId(), email == null ? null : email.getSource(),
                classification != null && classification.path("promptInjectionSuspected").asBoolean(false) ? Boolean.TRUE : null);
    }

    public EmailView emailView(Email e) {
        List<AttachmentView> atts = attachments.findByEmailIdOrderByIdAsc(e.getId()).stream()
                .map(a -> new AttachmentView(a.getId(), a.getFilename(), a.getMimeType(), a.getSizeBytes()))
                .toList();
        return emailView(e, atts);
    }

    public List<EmailView> emailViews(List<Email> list) {
        Map<Long, List<EmailAttachment>> byEmail = attachments.findByEmailIdIn(list.stream().map(Email::getId).toList())
                .stream().collect(Collectors.groupingBy(EmailAttachment::getEmailId, LinkedHashMap::new, Collectors.toList()));
        return list.stream().map(e -> emailView(e, byEmail.getOrDefault(e.getId(), List.of()).stream()
                .map(a -> new AttachmentView(a.getId(), a.getFilename(), a.getMimeType(), a.getSizeBytes())).toList())).toList();
    }

    private static EmailView emailView(Email e, List<AttachmentView> atts) {
        return new EmailView(e.getId(), e.getSender(), e.getSenderName(), e.getSubject(), e.getBody(), e.getReceivedAt(),
                e.getThreadId(), atts, Json.splitCsv(e.getLabels()), e.getIsPitch(), e.getIsFollowUp(), e.getCategory(),
                e.getSource());
    }

    public DraftView draftView(EmailDraft d) {
        return new DraftView(d.getId(), d.getRecipient(), d.getRecipientName(), d.getSubject(), d.getBody(),
                d.getPurpose(), d.getStatus(), d.isNeedsHumanReview(), list(d.getReviewReasonsJson()),
                d.getCreatedAt(), d.getSentAt(), d.getFailureReason());
    }

    /** Latest execution per agent; pipeline agents not yet run show as PENDING once processing started. */
    private List<AgentStatusView> agents(Workflow wf) {
        Map<String, AgentExecution> latest = new LinkedHashMap<>();
        for (AgentExecution ex : executions.findByWorkflowIdOrderByIdAsc(wf.getId())) {
            latest.put(ex.getAgentName(), ex);
        }
        boolean pipelineStarted = latest.containsKey(Agent.DOCUMENT_AGENT.name())
                || "DOCUMENT".equals(wf.getCurrentStep());
        List<AgentStatusView> out = new ArrayList<>();
        for (Agent agent : Agent.values()) {
            AgentExecution ex = latest.get(agent.name());
            if (ex != null) {
                out.add(new AgentStatusView(agent.name(), agent.label(), ex.getStatus(), ex.getErrorMessage(),
                        ex.getStartedAt(), ex.getCompletedAt(), ex.getLatencyMs(), ex.getPromptTokens(),
                        ex.getCompletionTokens(), ex.getModel(), ex.getEstimatedCostUsd(), ex.getAttempts()));
            } else if (pipelineStarted && PIPELINE.contains(agent)) {
                boolean skipped = agent == Agent.VERIFICATION_AGENT && wf.getAnalysisJson() != null;
                out.add(new AgentStatusView(agent.name(), agent.label(), skipped ? "SKIPPED" : "PENDING", null,
                        null, null, 0, 0, 0, null, null, 0));
            }
        }
        return out;
    }

    private String result(JsonNode analysis, JsonNode classification) {
        if (analysis != null) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode f : analysis.path("brief").path("executiveSummary")) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(f.path("statement").asText());
            }
            if (sb.length() > 0) {
                return Json.truncate(sb.toString(), 800);
            }
        }
        return Json.text(classification, "reason");
    }

    private List<String> list(String jsonArray) {
        List<String> out = new ArrayList<>();
        JsonNode n = json.read(jsonArray);
        if (n != null && n.isArray()) {
            n.forEach(v -> out.add(v.asText()));
        }
        return out;
    }
}
