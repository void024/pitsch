package com.pitsch.backend.workflow;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pitsch.backend.activity.ActivityService;
import com.pitsch.backend.ai.Agent;
import com.pitsch.backend.ai.AgentResult;
import com.pitsch.backend.auth.User;
import com.pitsch.backend.auth.UserRepository;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.config.AppConfig.WorkflowRunner;
import com.pitsch.backend.email.Email;
import com.pitsch.backend.email.EmailAttachment;
import com.pitsch.backend.email.EmailAttachmentRepository;
import com.pitsch.backend.email.EmailRepository;
import com.pitsch.backend.event.CalendarEvent;
import com.pitsch.backend.event.CalendarEventRepository;
import com.pitsch.backend.event.EventController;
import com.pitsch.backend.notification.NotificationService;
import com.pitsch.backend.pitch.Pitch;
import com.pitsch.backend.pitch.PitchRepository;
import com.pitsch.backend.user.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import static com.pitsch.backend.workflow.WorkflowStatus.AWAITING_USER;
import static com.pitsch.backend.workflow.WorkflowStatus.CLASSIFYING;
import static com.pitsch.backend.workflow.WorkflowStatus.COMPLETED;
import static com.pitsch.backend.workflow.WorkflowStatus.FAILED;
import static com.pitsch.backend.workflow.WorkflowStatus.NOT_PITCH;
import static com.pitsch.backend.workflow.WorkflowStatus.PROCESSING;
import static com.pitsch.backend.workflow.WorkflowStatus.RECEIVED;
import static com.pitsch.backend.workflow.WorkflowStatus.STOPPED;
import static com.pitsch.backend.workflow.WorkflowStatus.WAITING_FOR_APPROVAL;

/**
 * The orchestrator: owns workflow state and decides what happens next. The AI agents only interpret
 * information; this class turns their results into state changes, notifications and actions.
 *
 * <pre>
 * email ─► CLASSIFYING ─┬─► NOT_PITCH                          (Handle anyway offered if the AI was unsure)
 *                       └─► AWAITING_USER ─(COMPLETE_WORKFLOW)─► PROCESSING: Document → Research →
 *                               │                                Verification → Analysis
 *                               │                                      ▼
 *                               └─(follow-up actions)────────► WAITING_FOR_APPROVAL ─► COMPLETED
 *     PLAN_MEETING → Calendar Agent → pick slot → Action Agent creates the meeting (with approval)
 *     PLAN_EMAIL_RESPONSE → Email Response Agent → edit → Send → Action Agent sends (with approval)
 *     STOP at any time → STOPPED · agent failure → FAILED (RETRY resumes where it stopped)
 * </pre>
 */
@Service
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);

    static final List<String> NEW_PITCH_ACTIONS = List.of("COMPLETE_WORKFLOW", "STOP");
    static final List<String> FOLLOW_UP_ACTIONS = List.of("COMPLETE_WORKFLOW", "STOP", "PLAN_MEETING", "PLAN_EMAIL_RESPONSE");
    static final List<String> REVIEW_ACTIONS = List.of("PLAN_MEETING", "PLAN_EMAIL_RESPONSE", "COMPLETE_WORKFLOW", "STOP");
    static final List<String> FAILED_ACTIONS = List.of("RETRY", "STOP");
    static final Set<String> EMAIL_PURPOSES = Set.of("ACKNOWLEDGE", "REQUEST_INFO", "PROPOSE_MEETING",
            "CONFIRM_MEETING", "DECLINE", "GENERAL_REPLY");

    private final WorkflowRepository workflows;
    private final EmailRepository emails;
    private final EmailAttachmentRepository attachments;
    private final PitchRepository pitches;
    private final EmailDraftRepository drafts;
    private final CalendarEventRepository events;
    private final UserRepository users;
    private final SettingsService settings;
    private final AgentRunner agents;
    private final AgentInputs inputs;
    private final ActionExecutor executor;
    private final NotificationService notifications;
    private final ActivityService activity;
    private final Json json;
    private final WorkflowRunner background;
    private final String frontendUrl;
    private final ConcurrentHashMap<Long, Object> locks = new ConcurrentHashMap<>();

    /** Thrown inside background steps when the user stopped the workflow meanwhile. */
    static final class StoppedException extends RuntimeException {
        StoppedException() {
            super("Workflow was stopped");
        }
    }

    public WorkflowEngine(WorkflowRepository workflows, EmailRepository emails, EmailAttachmentRepository attachments,
                          PitchRepository pitches, EmailDraftRepository drafts, CalendarEventRepository events,
                          UserRepository users, SettingsService settings, AgentRunner agents, AgentInputs inputs,
                          ActionExecutor executor, NotificationService notifications, ActivityService activity,
                          Json json, WorkflowRunner background, @Value("${pitsch.frontend-url}") String frontendUrl) {
        this.workflows = workflows;
        this.emails = emails;
        this.attachments = attachments;
        this.pitches = pitches;
        this.drafts = drafts;
        this.events = events;
        this.users = users;
        this.settings = settings;
        this.agents = agents;
        this.inputs = inputs;
        this.executor = executor;
        this.notifications = notifications;
        this.activity = activity;
        this.json = json;
        this.background = background;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
    }

    // =====================================================================================
    // Entry points (called from controllers)
    // =====================================================================================

    /** A new email arrived: create its workflow and classify it in the background. */
    public Workflow startForEmail(Email email) {
        Workflow wf = new Workflow();
        wf.setUserId(email.getUserId());
        wf.setEmailId(email.getId());
        wf.setStatus(RECEIVED);
        wf.setCurrentStep("RECEIVED");
        wf.setAvailableActions("");
        wf.setSummary(Json.truncate(email.getSubject() != null && !email.getSubject().isBlank()
                ? email.getSubject() : "Email from " + email.getSender(), 1000));
        wf = workflows.save(wf);
        activity.log(wf.getUserId(), "WORKFLOW", "Email received from " + email.getSender() + ": " + wf.getSummary());
        runInBackground(wf.getId(), "CLASSIFYING", this::classify);
        return wf;
    }

    public Workflow applyAction(Long userId, Long workflowId, ActionRequest req) {
        if (req == null || req.action() == null) {
            throw ApiException.badRequest("action is required");
        }
        WorkflowAction action;
        try {
            action = WorkflowAction.valueOf(req.action().trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown action '" + req.action() + "'. Use exact values like PLAN_MEETING.");
        }
        synchronized (lock(workflowId)) {
            Workflow wf = find(userId, workflowId);
            List<String> available = Json.splitCsv(wf.getAvailableActions());
            if (!available.contains(action.name())) {
                throw ApiException.conflict("Action " + action + " is not available while the workflow is "
                        + wf.getStatus() + ". Available: " + (available.isEmpty() ? "none" : String.join(", ", available)));
            }
            User user = user(userId);
            switch (action) {
                case STOP -> stop(wf, user);
                case RETRY -> retry(wf);
                case COMPLETE_WORKFLOW -> {
                    if (NOT_PITCH.equals(wf.getStatus())) {
                        convertToPitch(wf);
                        startPipeline(wf);
                    } else if (closesOnComplete(wf)) {
                        complete(wf, user);
                    } else {
                        startPipeline(wf);
                    }
                }
                case PLAN_MEETING -> {
                    requirePitch(wf);
                    Integer duration = req.durationMinutes();
                    setProcessing(wf, "CALENDAR");
                    runInBackground(wf.getId(), "CALENDAR", id -> planMeeting(id, duration));
                }
                case PLAN_EMAIL_RESPONSE -> {
                    requirePitch(wf);
                    String purpose = choosePurpose(wf, req);
                    setProcessing(wf, "EMAIL_DRAFTING");
                    runInBackground(wf.getId(), "EMAIL_DRAFTING", id -> planEmail(id, purpose, req));
                }
                default -> throw ApiException.badRequest("Unsupported action " + action);
            }
            return load(workflowId);
        }
    }

    /** The investor picked a slot: the Action Agent prepares the Google Calendar event, then we execute it. */
    public Workflow approveMeeting(Long userId, Long workflowId, String start, String end, String title) {
        synchronized (lock(workflowId)) {
            Workflow wf = find(userId, workflowId);
            if (!WAITING_FOR_APPROVAL.equals(wf.getStatus()) && !AWAITING_USER.equals(wf.getStatus())) {
                throw ApiException.conflict("A meeting can only be scheduled while the workflow is waiting for you (status: "
                        + wf.getStatus() + ").");
            }
            requirePitch(wf);
            User user = user(userId);
            String tz = settings.timezone(userId);
            ZoneId zone = ZoneId.of(tz);
            Instant s = EventController.parseInstant(start, zone, "start");
            Instant e = EventController.parseInstant(end, zone, "end");
            if (!e.isAfter(s)) {
                throw ApiException.badRequest("end must be after start");
            }
            Pitch pitch = pitch(wf);
            Email email = email(wf);
            ObjectNode in = inputs.action("MEETING_APPROVED", wf, email, pitch, briefUrl(wf), json.read(wf.getAnalysisJson()));
            ObjectNode meeting = in.putObject("meeting");
            meeting.put("start", AgentInputs.iso(s, zone));
            meeting.put("end", AgentInputs.iso(e, zone));
            meeting.put("timezone", tz);
            if (title != null && !title.isBlank()) {
                meeting.put("title", title.trim());
            }
            ArrayNode attendees = meeting.putArray("attendeeEmails");
            if (pitch.getFounderEmail() != null) {
                attendees.add(pitch.getFounderEmail());
            }
            meeting.put("addVideoConference", true);
            inputs.addApproval(in, user);

            JsonNode data = runActionAgentOrThrow(wf, in, "Could not schedule the meeting");
            ActionExecutor.Report report = executor.execute(wf, data);
            wf.setMeetingStart(s);
            wf.setMeetingEnd(e);
            wf.setMeetingEventId(report.createdEventId());
            waitForUser(wf, "MEETING_SCHEDULED", REVIEW_ACTIONS, null);
            wf.setRecommendedAction("PLAN_EMAIL_RESPONSE");
            return workflows.save(wf);
        }
    }

    /** The investor clicked Send on a draft: the Action Agent prepares the Gmail send (with approval). */
    public Workflow sendDraft(Long userId, Long draftId, String subject, String body) {
        EmailDraft draft = drafts.findByIdAndUserId(draftId, userId).orElseThrow(() -> ApiException.notFound("Draft"));
        synchronized (lock(draft.getWorkflowId())) {
            if (!"DRAFT".equals(draft.getStatus())) {
                throw ApiException.conflict("This draft was already " + draft.getStatus().toLowerCase(Locale.ROOT) + ".");
            }
            if (subject != null && !subject.isBlank()) {
                draft.setSubject(subject.trim());
            }
            if (body != null && !body.isBlank()) {
                draft.setBody(body);
            }
            Workflow wf = find(userId, draft.getWorkflowId());
            if (WorkflowStatus.isFinal(wf.getStatus())) {
                throw ApiException.conflict("The workflow is " + wf.getStatus() + ".");
            }
            Email email = email(wf);
            ObjectNode in = inputs.action("EMAIL_APPROVED", wf, email, null, null, null);
            ObjectNode em = in.putObject("email");
            em.put("recipient", draft.getRecipient());
            em.put("subject", draft.getSubject());
            em.put("body", draft.getBody());
            if (email.getThreadId() != null) {
                em.put("threadId", email.getThreadId());
            }
            if (email.getMessageId() != null) {
                em.put("inReplyToMessageId", email.getMessageId());
            }
            inputs.addApproval(in, user(userId));

            JsonNode data = runActionAgentOrThrow(wf, in, "Could not send the email");
            executor.execute(wf, data);
            draft.setStatus("SENT");
            draft.setSentAt(Instant.now());
            drafts.save(draft);
            waitForUser(wf, "EMAIL_SENT", REVIEW_ACTIONS, null);
            wf.setRecommendedAction("COMPLETE_WORKFLOW");
            return workflows.save(wf);
        }
    }

    public Workflow cancelDraft(Long userId, Long draftId) {
        EmailDraft draft = drafts.findByIdAndUserId(draftId, userId).orElseThrow(() -> ApiException.notFound("Draft"));
        synchronized (lock(draft.getWorkflowId())) {
            if ("DRAFT".equals(draft.getStatus())) {
                draft.setStatus("CANCELLED");
                drafts.save(draft);
            }
            Workflow wf = find(userId, draft.getWorkflowId());
            if (!WorkflowStatus.isFinal(wf.getStatus()) && !PROCESSING.equals(wf.getStatus())) {
                wf.setCurrentStep("EMAIL_DRAFT_CANCELLED");
                workflows.save(wf);
            }
            return wf;
        }
    }

    public EmailDraft updateDraft(Long userId, Long draftId, String subject, String body) {
        EmailDraft draft = drafts.findByIdAndUserId(draftId, userId).orElseThrow(() -> ApiException.notFound("Draft"));
        if (!"DRAFT".equals(draft.getStatus())) {
            throw ApiException.conflict("Only drafts that haven't been sent can be edited.");
        }
        if (subject != null) {
            draft.setSubject(subject);
        }
        if (body != null) {
            draft.setBody(body);
        }
        return drafts.save(draft);
    }

    // =====================================================================================
    // Background steps
    // =====================================================================================

    void classify(Long workflowId) {
        update(workflowId, wf -> {
            wf.setStatus(CLASSIFYING);
            wf.setCurrentStep("CLASSIFYING");
            wf.setAvailableActions("");
            wf.setError(null);
        });
        Workflow wf = load(workflowId);
        Email email = email(wf);
        List<EmailAttachment> atts = attachments.findByEmailIdOrderByIdAsc(email.getId());
        AgentResult r = agents.run(workflowId, Agent.EMAIL_CLASSIFIER, inputs.classifier(email, atts, candidatesFor(email)));
        if (!r.success()) {
            fail(workflowId, "CLASSIFYING", "Email classification failed: " + r.errorMessage());
            return;
        }
        JsonNode c = r.data();
        boolean isPitch = Json.bool(c, "isPitch");
        Long previousId = parseLong(Json.text(c, "previousPitchId"));
        Pitch linked = previousId == null ? null : pitches.findByIdAndUserId(previousId, wf.getUserId()).orElse(null);

        email.setIsPitch(isPitch);
        email.setIsFollowUp(Json.bool(c, "isFollowUp"));
        email.setCategory(Json.text(c, "category"));
        email.setPreviousPitchId(linked == null ? null : linked.getId());
        emails.save(email);

        String event;
        if (!isPitch) {
            boolean unsure = Json.bool(c, "needsHumanReview");
            update(workflowId, w -> {
                w.setClassificationJson(json.write(c));
                mergeReview(w, "Classifier", c);
                w.setType("NOT_PITCH");
                w.setStatus(NOT_PITCH);
                w.setCurrentStep("DONE");
                w.setRecommendedAction("STOP");
                // If the classifier wasn't confident, let the investor treat it as a pitch anyway.
                w.setAvailableActions(unsure ? "COMPLETE_WORKFLOW" : "");
            });
            activity.log(wf.getUserId(), "WORKFLOW", "Not a pitch: \"" + wf.getSummary() + "\" ("
                    + (Json.text(c, "category") == null ? "NOT_PITCH" : Json.text(c, "category")) + ")");
            if (unsure) {
                notifications.notify(wf.getUserId(), "ACTION_REQUIRED", "Possible pitch",
                        "Pitsch wasn't sure whether \"" + wf.getSummary() + "\" is a pitch. You can handle it anyway.",
                        workflowId);
            }
            return;
        }

        Pitch pitch;
        if (Json.bool(c, "isFollowUp") && linked != null) {
            pitch = linked;
            addThread(pitch, email.getThreadId());
            pitch.setLatestWorkflowId(workflowId);
            pitches.save(pitch);
            String rec = Json.text(c, "recommendedAction");
            String recommended = rec == null || "ASK_TO_HANDLE".equals(rec) ? "COMPLETE_WORKFLOW" : rec;
            update(workflowId, w -> {
                w.setClassificationJson(json.write(c));
                mergeReview(w, "Classifier", c);
                w.setType("FOLLOW_UP");
                w.setPitchId(pitch.getId());
                w.setRecommendedAction(recommended);
                waitForUser(w, "AWAITING_DECISION", FOLLOW_UP_ACTIONS, AWAITING_USER);
            });
            notifications.notify(wf.getUserId(), "FOLLOW_UP_DETECTED", "Follow-up detected",
                    name(pitch) + " followed up on their pitch.", workflowId);
            event = "FOLLOW_UP_DETECTED";
        } else {
            pitch = createPitch(wf, email, c);
            update(workflowId, w -> {
                w.setClassificationJson(json.write(c));
                mergeReview(w, "Classifier", c);
                w.setType("NEW_PITCH");
                w.setPitchId(pitch.getId());
                w.setRecommendedAction("ASK_TO_HANDLE");
                waitForUser(w, "AWAITING_DECISION", NEW_PITCH_ACTIONS, AWAITING_USER);
            });
            notifications.notify(wf.getUserId(), "PITCH_DETECTED", "New pitch detected",
                    name(pitch) + " — would you like Pitsch to handle this pitch?", workflowId);
            event = "PITCH_DETECTED";
        }
        activity.log(wf.getUserId(), "WORKFLOW", ("PITCH_DETECTED".equals(event) ? "New pitch: " : "Follow-up: ") + name(pitch));
        runActionAgent(workflowId, event, null);
    }

    void runPipeline(Long workflowId) {
        runActionAgent(workflowId, "PROCESSING_STARTED", null);
        Workflow wf = load(workflowId);
        Email email = email(wf);
        Pitch pitch = pitch(wf);

        // ---- 1. Document Agent (required) ----
        JsonNode doc = json.read(wf.getDocumentJson());
        if (doc == null) {
            step(workflowId, "DOCUMENT");
            AgentResult r = agents.run(workflowId, Agent.DOCUMENT_AGENT,
                    inputs.document(pitch, email, attachments.findByEmailIdOrderByIdAsc(email.getId())));
            if (!r.success()) {
                fail(workflowId, "DOCUMENT", "Pitch deck processing failed: " + r.errorMessage());
                return;
            }
            JsonNode docData = r.data();
            update(workflowId, w -> {
                w.setDocumentJson(json.write(docData));
                mergeReview(w, "Document", docData);
            });
            pitch = updatePitchFromDocument(pitch.getId(), docData);
            doc = docData;
        }

        // ---- 2. Research Agent (optional: on failure the brief is pitch-only) ----
        JsonNode research = json.read(load(workflowId).getResearchJson());
        if (research == null) {
            step(workflowId, "RESEARCH");
            AgentResult r = agents.run(workflowId, Agent.RESEARCH_AGENT, inputs.research(pitch, doc));
            if (r.success()) {
                JsonNode researchData = r.data();
                update(workflowId, w -> {
                    w.setResearchJson(json.write(researchData));
                    mergeReview(w, "Research", researchData);
                });
                research = researchData;
            } else {
                String msg = "Research failed (" + r.errorMessage() + "); the brief is based on the pitch only.";
                update(workflowId, w -> addWarning(w, msg));
            }
        }

        // ---- 3. Verification Agent (optional; needs research) ----
        JsonNode verification = json.read(load(workflowId).getVerificationJson());
        if (verification == null && research != null) {
            step(workflowId, "VERIFICATION");
            AgentResult r = agents.run(workflowId, Agent.VERIFICATION_AGENT, inputs.verification(pitch, doc, research));
            if (r.success()) {
                JsonNode verData = r.data();
                update(workflowId, w -> {
                    w.setVerificationJson(json.write(verData));
                    mergeReview(w, "Verification", verData);
                });
                verification = verData;
            } else {
                String msg = "Claim verification failed (" + r.errorMessage() + "); claims are shown as not checked.";
                update(workflowId, w -> addWarning(w, msg));
            }
        }

        // ---- 4. Analysis Agent (required) ----
        step(workflowId, "ANALYSIS");
        AgentResult r = agents.run(workflowId, Agent.ANALYSIS_AGENT, inputs.analysis(pitch, doc, research, verification));
        if (!r.success()) {
            fail(workflowId, "ANALYSIS", "Building the research brief failed: " + r.errorMessage());
            return;
        }
        JsonNode analysis = r.data();
        boolean meetingRequested = Json.bool(json.read(load(workflowId).getClassificationJson()), "meetingRequested");
        update(workflowId, w -> {
            w.setAnalysisJson(json.write(analysis));
            w.setBriefMarkdown(Json.text(analysis, "markdown"));
            mergeReview(w, "Analysis", analysis);
        });
        Pitch p = pitches.findById(pitch.getId()).orElse(pitch);
        p.setLatestBriefWorkflowId(workflowId);
        p.setLatestWorkflowId(workflowId);
        pitches.save(p);
        runActionAgent(workflowId, "BRIEF_READY", null);
        update(workflowId, w -> {
            waitForUser(w, "BRIEF_READY", REVIEW_ACTIONS, WAITING_FOR_APPROVAL);
            w.setRecommendedAction(meetingRequested ? "PLAN_MEETING" : "PLAN_EMAIL_RESPONSE");
        });
        int claims = analysis.path("brief").path("claimsMatrix").size();
        notifications.notify(p.getUserId(), "ACTION_REQUIRED", "Research brief ready",
                name(p) + ": " + claims + " claims checked. Review the brief and choose a next step.", workflowId);
        activity.log(p.getUserId(), "WORKFLOW", "Research brief ready: " + name(p));
    }

    void planMeeting(Long workflowId, Integer durationMinutes) {
        Workflow wf = load(workflowId);
        Pitch pitch = pitch(wf);
        Email email = email(wf);
        String tz = settings.timezone(wf.getUserId());
        Instant now = Instant.now();
        List<CalendarEvent> busy = events.findByUserIdAndEndTimeAfterAndStartTimeBeforeOrderByStartTimeAsc(
                wf.getUserId(), now, now.plus(Duration.ofDays(21)));
        AgentResult r = agents.run(workflowId, Agent.CALENDAR_AGENT, inputs.calendar(pitch, email,
                json.read(wf.getClassificationJson()), tz, busy, durationMinutes));
        if (!r.success()) {
            backToReview(workflowId, "Could not find meeting slots: " + r.errorMessage());
            return;
        }
        JsonNode data = r.data();
        int count = data.path("slots").size();
        update(workflowId, w -> {
            w.setCalendarJson(json.write(data));
            mergeReview(w, "Calendar", data);
            waitForUser(w, "MEETING_SLOTS_READY", REVIEW_ACTIONS, WAITING_FOR_APPROVAL);
            w.setRecommendedAction(null);
        });
        notifications.notify(wf.getUserId(), "MEETING_READY", "Meeting slots ready",
                count == 0 ? "No free slot matched the constraints for " + name(pitch) + "."
                        : count + " suggested times for " + name(pitch) + ". Pick one to schedule.", workflowId);
    }

    void planEmail(Long workflowId, String purpose, ActionRequest req) {
        Workflow wf = load(workflowId);
        Pitch pitch = pitch(wf);
        Email email = email(wf);
        User user = user(wf.getUserId());
        JsonNode analysis = json.read(wf.getAnalysisJson());
        JsonNode calendar = json.read(wf.getCalendarJson());
        List<String> questions = req.questions() != null && !req.questions().isEmpty()
                ? req.questions() : questionsFromBrief(analysis);
        String finalPurpose = "REQUEST_INFO".equals(purpose) && questions.isEmpty()
                && (req.instructions() == null || req.instructions().isBlank()) ? "ACKNOWLEDGE" : purpose;

        AgentResult r = agents.run(workflowId, Agent.EMAIL_RESPONSE_AGENT, inputs.emailResponse(finalPurpose, pitch,
                email, user, settings.forUser(user.getId()), settings.timezone(user.getId()),
                "REQUEST_INFO".equals(finalPurpose) ? questions : List.of(),
                "PROPOSE_MEETING".equals(finalPurpose) && calendar != null ? calendar.path("slots") : null,
                "CONFIRM_MEETING".equals(finalPurpose) ? wf.getMeetingStart() : null,
                "CONFIRM_MEETING".equals(finalPurpose) ? wf.getMeetingEnd() : null,
                req.instructions()));
        if (!r.success()) {
            backToReview(workflowId, "Could not draft the email: " + r.errorMessage());
            return;
        }
        JsonNode data = r.data();
        EmailDraft draft = new EmailDraft();
        draft.setUserId(wf.getUserId());
        draft.setWorkflowId(workflowId);
        draft.setPitchId(pitch.getId());
        draft.setRecipient(Json.text(data, "recipient"));
        draft.setRecipientName(Json.text(data, "recipientName"));
        draft.setSubject(Json.text(data, "subject"));
        draft.setBody(data.path("body").asText(""));
        draft.setPurpose(Json.text(data, "purpose"));
        draft.setNeedsHumanReview(Json.bool(data, "needsHumanReview"));
        draft.setReviewReasonsJson(json.write(data.path("reviewReasons")));
        drafts.save(draft);
        update(workflowId, w -> waitForUser(w, "EMAIL_DRAFT_READY", REVIEW_ACTIONS, WAITING_FOR_APPROVAL));
        notifications.notify(wf.getUserId(), "EMAIL_READY", "Email draft ready",
                "Review the draft to " + draft.getRecipient() + " before sending.", workflowId);
    }

    // =====================================================================================
    // Synchronous transitions
    // =====================================================================================

    private void stop(Workflow wf, User user) {
        wf.setStatus(STOPPED);
        wf.setCurrentStep("STOPPED");
        wf.setAvailableActions("");
        wf.setRecommendedAction(null);
        wf.setCompletedAt(Instant.now());
        workflows.save(wf);
        activity.log(user.getId(), "WORKFLOW", "Workflow stopped: " + wf.getSummary());
        runActionAgent(wf.getId(), "WORKFLOW_STOPPED", null);
    }

    private void complete(Workflow wf, User user) {
        wf.setStatus(COMPLETED);
        wf.setCurrentStep("DONE");
        wf.setAvailableActions("");
        wf.setRecommendedAction(null);
        wf.setCompletedAt(Instant.now());
        workflows.save(wf);
        runActionAgent(wf.getId(), "WORKFLOW_COMPLETED", null);
        notifications.notify(user.getId(), "WORKFLOW_COMPLETED", "Workflow completed", wf.getSummary(), wf.getId());
        activity.log(user.getId(), "WORKFLOW", "Workflow completed: " + wf.getSummary());
    }

    private void retry(Workflow wf) {
        if (wf.getClassificationJson() == null) {
            wf.setStatus(CLASSIFYING);
            wf.setCurrentStep("CLASSIFYING");
            wf.setAvailableActions("STOP");
            wf.setError(null);
            workflows.save(wf);
            runInBackground(wf.getId(), "CLASSIFYING", this::classify);
        } else {
            startPipeline(wf);
        }
    }

    private void startPipeline(Workflow wf) {
        setProcessing(wf, "DOCUMENT");
        runInBackground(wf.getId(), "PIPELINE", this::runPipeline);
    }

    private void setProcessing(Workflow wf, String step) {
        wf.setStatus(PROCESSING);
        wf.setCurrentStep(step);
        wf.setAvailableActions("STOP");
        wf.setError(null);
        workflows.save(wf);
    }

    /** COMPLETE_WORKFLOW closes the workflow once a brief exists, or when the founder ended the conversation. */
    private boolean closesOnComplete(Workflow wf) {
        if (wf.getAnalysisJson() != null) {
            return true;
        }
        return "FOLLOW_UP".equals(wf.getType()) && Json.bool(json.read(wf.getClassificationJson()), "workflowClosed");
    }

    private void convertToPitch(Workflow wf) {
        Email email = email(wf);
        Pitch pitch = createPitch(wf, email, json.read(wf.getClassificationJson()));
        email.setIsPitch(true);
        emails.save(email);
        wf.setType("NEW_PITCH");
        wf.setPitchId(pitch.getId());
        activity.log(wf.getUserId(), "WORKFLOW", "Treated as a pitch by the investor: " + wf.getSummary());
    }

    private String choosePurpose(Workflow wf, ActionRequest req) {
        String purpose = req.purpose() == null || req.purpose().isBlank() ? null : req.purpose().trim().toUpperCase(Locale.ROOT);
        JsonNode calendar = json.read(wf.getCalendarJson());
        boolean hasSlots = calendar != null && calendar.path("slots").size() > 0;
        if (purpose == null) {
            if (wf.getMeetingStart() != null) {
                purpose = "CONFIRM_MEETING";
            } else if (hasSlots) {
                purpose = "PROPOSE_MEETING";
            } else if (wf.getAnalysisJson() != null) {
                purpose = "REQUEST_INFO";
            } else {
                purpose = req.instructions() != null && !req.instructions().isBlank() ? "GENERAL_REPLY" : "ACKNOWLEDGE";
            }
        }
        if (!EMAIL_PURPOSES.contains(purpose)) {
            throw ApiException.badRequest("purpose must be one of " + EMAIL_PURPOSES);
        }
        if ("PROPOSE_MEETING".equals(purpose) && !hasSlots) {
            throw ApiException.badRequest("Plan a meeting first so there are slots to propose.");
        }
        if ("CONFIRM_MEETING".equals(purpose) && wf.getMeetingStart() == null) {
            throw ApiException.badRequest("Schedule the meeting first, then confirm it by email.");
        }
        if ("GENERAL_REPLY".equals(purpose) && (req.instructions() == null || req.instructions().isBlank())) {
            throw ApiException.badRequest("GENERAL_REPLY needs instructions for what to say.");
        }
        return purpose;
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    private void runInBackground(Long workflowId, String stepName, Consumer<Long> step) {
        background.submit(() -> {
            try {
                step.accept(workflowId);
            } catch (StoppedException e) {
                log.info("Workflow {} stopped during {}", workflowId, stepName);
            } catch (RuntimeException e) {
                log.error("Workflow {} failed during {}", workflowId, stepName, e);
                fail(workflowId, stepName, "Unexpected error during " + stepName + ": " + e.getMessage());
            }
        });
    }

    /** Load–modify–save under the workflow lock; aborts if the user stopped the workflow meanwhile. */
    private Workflow update(Long workflowId, Consumer<Workflow> change) {
        synchronized (lock(workflowId)) {
            Workflow wf = load(workflowId);
            if (STOPPED.equals(wf.getStatus())) {
                throw new StoppedException();
            }
            change.accept(wf);
            return workflows.save(wf);
        }
    }

    private void step(Long workflowId, String step) {
        update(workflowId, w -> {
            w.setStatus(PROCESSING);
            w.setCurrentStep(step);
        });
    }

    private void fail(Long workflowId, String step, String message) {
        synchronized (lock(workflowId)) {
            Workflow wf = load(workflowId);
            if (STOPPED.equals(wf.getStatus())) {
                return;
            }
            wf.setStatus(FAILED);
            wf.setCurrentStep(step);
            wf.setError(Json.truncate(message, 2000));
            wf.setAvailableActions(Json.joinCsv(FAILED_ACTIONS));
            workflows.save(wf);
            notifications.notify(wf.getUserId(), "AGENT_FAILED", "Agent failed", message, workflowId);
            activity.log(wf.getUserId(), "WORKFLOW", "Workflow failed at " + step + ": " + message);
        }
    }

    /** Meeting/email planning failures don't kill the workflow: go back to review with the error shown. */
    private void backToReview(Long workflowId, String message) {
        Workflow wf = update(workflowId, w -> {
            waitForUser(w, "REVIEW", REVIEW_ACTIONS, WAITING_FOR_APPROVAL);
            w.setError(Json.truncate(message, 2000));
        });
        notifications.notify(wf.getUserId(), "AGENT_FAILED", "Agent failed", message, workflowId);
    }

    private static void waitForUser(Workflow w, String step, List<String> actions, String status) {
        w.setStatus(status == null ? WAITING_FOR_APPROVAL : status);
        w.setCurrentStep(step);
        w.setAvailableActions(Json.joinCsv(actions));
        w.setError(null);
    }

    /** Runs the Action Agent for an event that needs no approval and executes its actions. Never fails the workflow. */
    private void runActionAgent(Long workflowId, String event, ObjectNode extra) {
        try {
            Workflow wf = load(workflowId);
            Email email = wf.getEmailId() == null ? null : emails.findById(wf.getEmailId()).orElse(null);
            Pitch pitch = wf.getPitchId() == null ? null : pitches.findById(wf.getPitchId()).orElse(null);
            ObjectNode in = inputs.action(event, wf, email, pitch, briefUrl(wf), json.read(wf.getAnalysisJson()));
            if (extra != null) {
                in.setAll(extra);
            }
            AgentResult r = agents.run(workflowId, Agent.ACTION_AGENT, in);
            if (!r.success()) {
                log.warn("Action agent failed for {} on workflow {}: {}", event, workflowId, r.errorMessage());
                return;
            }
            executor.execute(wf, r.data());
            String executed = wf.getExecutedActionIds();
            synchronized (lock(workflowId)) {
                Workflow fresh = load(workflowId);
                fresh.setExecutedActionIds(executed);
                workflows.save(fresh);
            }
        } catch (RuntimeException e) {
            log.warn("Action handling failed for {} on workflow {}", event, workflowId, e);
        }
    }

    private JsonNode runActionAgentOrThrow(Workflow wf, ObjectNode in, String what) {
        AgentResult r = agents.run(wf.getId(), Agent.ACTION_AGENT, in);
        if (!r.success()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, what + ": " + r.errorMessage());
        }
        if (r.data().path("blockedActionIds").size() > 0) {
            throw ApiException.conflict(what + ": the action still needs approval.");
        }
        return r.data();
    }

    private Pitch createPitch(Workflow wf, Email email, JsonNode c) {
        List<String> companies = Json.strings(c, "detectedCompanies");
        boolean forwarded = Json.bool(c, "isForwarded");
        String original = Json.text(c, "originalSenderEmail");
        String founderEmail = forwarded && original != null ? original.toLowerCase(Locale.ROOT) : email.getSender();
        String domain = Json.domainOf(founderEmail);

        Pitch p = new Pitch();
        p.setUserId(wf.getUserId());
        p.setCompanyName(AgentInputs.firstText(companies.isEmpty() ? null : companies.get(0),
                Json.isFreeMail(domain) ? null : domain, email.getSenderName(), "Unknown company"));
        p.setFounderName(forwarded ? null : email.getSenderName());
        p.setFounderEmail(founderEmail);
        p.setCompanyDomain(Json.isFreeMail(domain) ? null : domain);
        p.setSource("EMAIL");
        p.setStatus("NEW");
        p.setFirstEmailId(email.getId());
        p.setLatestWorkflowId(wf.getId());
        addThread(p, email.getThreadId());
        return pitches.save(p);
    }

    private Pitch updatePitchFromDocument(Long pitchId, JsonNode doc) {
        Pitch p = pitches.findById(pitchId).orElseThrow(() -> ApiException.notFound("Pitch"));
        JsonNode company = doc.path("company");
        if (Json.text(company, "name") != null) {
            p.setCompanyName(Json.text(company, "name"));
        }
        if (Json.text(company, "website") != null) {
            p.setWebsite(Json.text(company, "website"));
            if (p.getCompanyDomain() == null) {
                p.setCompanyDomain(Json.domainOf(Json.text(company, "website")));
            }
        }
        if (Json.text(company, "sector") != null) {
            p.setSector(Json.text(company, "sector"));
        }
        if (Json.text(company, "stage") != null) {
            p.setStage(Json.text(company, "stage"));
        }
        if (Json.text(company, "oneLiner") != null) {
            p.setOneLiner(Json.truncate(Json.text(company, "oneLiner"), 1000));
        }
        String amount = Json.text(doc.path("fundraise"), "amountRequested");
        if (amount != null) {
            p.setAmountRequested(amount);
        }
        JsonNode founders = doc.path("founders");
        if (p.getFounderName() == null && founders.size() > 0 && Json.text(founders.get(0), "name") != null) {
            p.setFounderName(Json.text(founders.get(0), "name"));
        }
        return pitches.save(p);
    }

    /** Existing pitches this email may belong to (same founder, company domain, thread, or company named). */
    private List<Pitch> candidatesFor(Email email) {
        String sender = email.getSender();
        String domain = Json.domainOf(sender);
        String text = (email.getSubject() == null ? "" : email.getSubject()) + "\n" + (email.getBody() == null ? "" : email.getBody());
        List<Pitch> out = new ArrayList<>();
        for (Pitch p : pitches.findByUserIdOrderByUpdatedAtDesc(email.getUserId())) {
            boolean match = sender.equalsIgnoreCase(p.getFounderEmail())
                    || (!Json.isFreeMail(domain) && domain.equalsIgnoreCase(p.getCompanyDomain()))
                    || (email.getThreadId() != null && Json.splitCsv(p.getThreadIds()).contains(email.getThreadId()))
                    || AgentInputs.mentions(text, p.getCompanyName());
            if (match) {
                out.add(p);
            }
            if (out.size() >= 20) {
                break;
            }
        }
        return out;
    }

    /** Founder-friendly questions for a REQUEST_INFO email, taken from the brief's open questions. */
    static List<String> questionsFromBrief(JsonNode analysis) {
        List<String> missing = new ArrayList<>();
        List<String> other = new ArrayList<>();
        List<String> claims = new ArrayList<>();
        if (analysis == null) {
            return missing;
        }
        for (JsonNode q : analysis.path("brief").path("openQuestions")) {
            String text = Json.text(q, "question");
            String origin = Json.text(q, "origin");
            if (text == null) {
                continue;
            }
            if ("MISSING_INFO".equals(origin)) {
                String item = text.replaceFirst("(?i)^not covered in the pitch:\\s*", "")
                        .replaceFirst("(?i)\\s+(not disclosed|not provided|not mentioned|missing)\\.?$", "").trim();
                if (!item.isEmpty()) {
                    missing.add("Could you share details on " + Character.toLowerCase(item.charAt(0)) + item.substring(1) + "?");
                }
            } else if ("ANALYSIS".equals(origin)) {
                other.add(text);
            } else if ("VERIFICATION".equals(origin)) {
                int open = text.lastIndexOf('(');
                String claim = open >= 0 ? text.substring(open + 1).replaceAll("\\)\\s*$", "") : text;
                claim = claim.replaceFirst("(?i)^the pitch claims\\s*", "").replaceAll("\\.$", "");
                claims.add("Could you share data supporting this: " + claim + "?");
            }
        }
        Set<String> all = new LinkedHashSet<>(missing);
        all.addAll(other);
        all.addAll(claims);
        List<String> unique = new ArrayList<>(all);
        return new ArrayList<>(unique.subList(0, Math.min(3, unique.size())));
    }

    private void mergeReview(Workflow w, String source, JsonNode data) {
        if (data == null) {
            return;
        }
        List<String> reasons = new ArrayList<>(listFrom(w.getReviewReasonsJson()));
        for (String r : Json.strings(data, "reviewReasons")) {
            String line = source + ": " + r;
            if (!reasons.contains(line)) {
                reasons.add(line);
            }
        }
        List<String> warnings = new ArrayList<>(listFrom(w.getWarningsJson()));
        for (String r : Json.strings(data, "warnings")) {
            String line = source + ": " + r;
            if (!warnings.contains(line)) {
                warnings.add(line);
            }
        }
        w.setReviewReasonsJson(json.write(reasons));
        w.setWarningsJson(json.write(warnings));
        w.setNeedsHumanReview(!reasons.isEmpty());
    }

    private void addWarning(Workflow w, String warning) {
        List<String> warnings = new ArrayList<>(listFrom(w.getWarningsJson()));
        warnings.add(warning);
        w.setWarningsJson(json.write(warnings));
    }

    List<String> listFrom(String jsonArray) {
        List<String> out = new ArrayList<>();
        JsonNode n = json.read(jsonArray);
        if (n != null && n.isArray()) {
            n.forEach(v -> out.add(v.asText()));
        }
        return out;
    }

    private static void addThread(Pitch p, String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return;
        }
        List<String> threads = Json.splitCsv(p.getThreadIds());
        if (!threads.contains(threadId)) {
            threads.add(threadId);
            p.setThreadIds(Json.truncate(Json.joinCsv(threads), 2000));
        }
    }

    private void requirePitch(Workflow wf) {
        if (wf.getPitchId() == null) {
            throw ApiException.conflict("This workflow isn't linked to a pitch yet.");
        }
    }

    private String briefUrl(Workflow wf) {
        return frontendUrl + "/workflows/" + wf.getId();
    }

    private static String name(Pitch p) {
        return p.getCompanyName() == null ? "A startup" : p.getCompanyName();
    }

    private static Long parseLong(String s) {
        try {
            return s == null ? null : Long.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Object lock(Long id) {
        return locks.computeIfAbsent(id, k -> new Object());
    }

    private Workflow load(Long id) {
        return workflows.findById(id).orElseThrow(() -> ApiException.notFound("Workflow"));
    }

    private Workflow find(Long userId, Long id) {
        return workflows.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Workflow"));
    }

    private User user(Long id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
    }

    private Email email(Workflow wf) {
        return emails.findById(wf.getEmailId()).orElseThrow(() -> ApiException.notFound("Email"));
    }

    private Pitch pitch(Workflow wf) {
        return pitches.findById(wf.getPitchId()).orElseThrow(() -> ApiException.notFound("Pitch"));
    }
}
