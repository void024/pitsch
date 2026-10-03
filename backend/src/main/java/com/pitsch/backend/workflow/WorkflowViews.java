package com.pitsch.backend.workflow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.pitsch.backend.ai.Agent;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.email.Email;
import com.pitsch.backend.email.EmailAttachmentRepository;
import com.pitsch.backend.email.EmailRepository;
import com.pitsch.backend.pitch.Pitch;
import com.pitsch.backend.pitch.PitchRepository;
import org.springframework.stereotype.Component;

/** JSON shapes returned to the frontend. */
@Component
public class WorkflowViews {

    public record AgentStatusView(String agent, String label, String status, String errorMessage,
                                  Instant startedAt, Instant completedAt, long latencyMs,
                                  long promptTokens, long completionTokens) { }

    public record AttachmentView(Long id, String filename, String mimeType, long sizeBytes) { }

    public record EmailView(Long id, String sender, String senderName, String subject, String body,
                            Instant receivedAt, String threadId, List<AttachmentView> attachments,
                            List<String> labels, Boolean isPitch, Boolean isFollowUp, String category) { }

    public record DraftView(Long id, String recipient, String recipientName, String subject, String body,
                            String purpose, String status, boolean needsHumanReview, List<String> reviewReasons,
                            Instant createdAt, Instant sentAt) { }

    public record MeetingView(Instant start, Instant end, Long eventId) { }

    /**
     * Workflow as returned by GET /api/workflows (list: summary fields only) and GET /api/workflows/{id}
     * (detail: everything). Field names follow the frontend/backend contract.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkflowView(Long id, Long workflowId, String type, String status, String currentStep,
                               String recommendedAction, List<String> availableActions, String prompt,
                               String result, String error, Instant createdAt, Instant updatedAt,
                               Instant completedAt, Long pitchId, String companyName, Long emailId, String sender,
                               String senderName, Double confidence, String classificationReason,
                               boolean needsHumanReview, List<String> reviewReasons, List<String> warnings,
                               List<AgentStatusView> agents, EmailView email, JsonNode brief, String briefMarkdown,
                               JsonNode slots, String slotsTimezone, MeetingView meeting, DraftView draft) { }

    private static final List<Agent> PIPELINE = List.of(Agent.DOCUMENT_AGENT, Agent.RESEARCH_AGENT,
            Agent.VERIFICATION_AGENT, Agent.ANALYSIS_AGENT);

    private final AgentExecutionRepository executions;
    private final EmailRepository emails;
    private final EmailAttachmentRepository attachments;
    private final PitchRepository pitches;
    private final EmailDraftRepository drafts;
    private final Json json;

    public WorkflowViews(AgentExecutionRepository executions, EmailRepository emails,
                         EmailAttachmentRepository attachments, PitchRepository pitches,
                         EmailDraftRepository drafts, Json json) {
        this.executions = executions;
        this.emails = emails;
        this.attachments = attachments;
        this.pitches = pitches;
        this.drafts = drafts;
        this.json = json;
    }

    public WorkflowView summary(Workflow wf) {
        return build(wf, false);
    }

    public WorkflowView detail(Workflow wf) {
        return build(wf, true);
    }

    private WorkflowView build(Workflow wf, boolean detail) {
        JsonNode classification = json.read(wf.getClassificationJson());
        JsonNode analysis = json.read(wf.getAnalysisJson());
        JsonNode calendar = json.read(wf.getCalendarJson());
        Email email = wf.getEmailId() == null ? null : emails.findById(wf.getEmailId()).orElse(null);
        Pitch pitch = wf.getPitchId() == null ? null : pitches.findById(wf.getPitchId()).orElse(null);

        return new WorkflowView(
                wf.getId(), wf.getId(), wf.getType(), wf.getStatus(), wf.getCurrentStep(), wf.getRecommendedAction(),
                Json.splitCsv(wf.getAvailableActions()), wf.getSummary(), result(analysis, classification), wf.getError(),
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
                wf.getMeetingStart() == null ? null : new MeetingView(wf.getMeetingStart(), wf.getMeetingEnd(), wf.getMeetingEventId()),
                detail ? drafts.findFirstByWorkflowIdOrderByIdDesc(wf.getId()).map(this::draftView).orElse(null) : null);
    }

    public EmailView emailView(Email e) {
        List<AttachmentView> atts = attachments.findByEmailIdOrderByIdAsc(e.getId()).stream()
                .map(a -> new AttachmentView(a.getId(), a.getFilename(), a.getMimeType(), a.getSizeBytes()))
                .toList();
        return new EmailView(e.getId(), e.getSender(), e.getSenderName(), e.getSubject(), e.getBody(), e.getReceivedAt(),
                e.getThreadId(), atts, Json.splitCsv(e.getLabels()), e.getIsPitch(), e.getIsFollowUp(), e.getCategory());
    }

    public DraftView draftView(EmailDraft d) {
        return new DraftView(d.getId(), d.getRecipient(), d.getRecipientName(), d.getSubject(), d.getBody(),
                d.getPurpose(), d.getStatus(), d.isNeedsHumanReview(), list(d.getReviewReasonsJson()),
                d.getCreatedAt(), d.getSentAt());
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
                        ex.getCompletionTokens()));
            } else if (pipelineStarted && PIPELINE.contains(agent)) {
                boolean skipped = agent == Agent.VERIFICATION_AGENT && wf.getAnalysisJson() != null;
                out.add(new AgentStatusView(agent.name(), agent.label(), skipped ? "SKIPPED" : "PENDING", null,
                        null, null, 0, 0, 0));
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
