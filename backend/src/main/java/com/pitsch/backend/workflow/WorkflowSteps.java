package com.pitsch.backend.workflow;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.pitsch.backend.activity.ActivityService;
import com.pitsch.backend.ai.Agent;
import com.pitsch.backend.ai.AgentResult;
import com.pitsch.backend.audit.AuditAction;
import com.pitsch.backend.audit.AuditService;
import com.pitsch.backend.auth.User;
import com.pitsch.backend.auth.UserRepository;
import com.pitsch.backend.billing.EntitlementService;
import com.pitsch.backend.billing.UsageMetric;
import com.pitsch.backend.billing.UsageService;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.email.Email;
import com.pitsch.backend.email.EmailAttachmentRepository;
import com.pitsch.backend.email.EmailRepository;
import com.pitsch.backend.integration.IntegrationService;
import com.pitsch.backend.jobs.JobQueue;
import com.pitsch.backend.jobs.JobType;
import com.pitsch.backend.notification.NotificationService;
import com.pitsch.backend.notification.NotificationType;
import com.pitsch.backend.observability.PitschMetrics;
import com.pitsch.backend.org.Organization;
import com.pitsch.backend.org.OrganizationRepository;
import com.pitsch.backend.pitch.Pitch;
import com.pitsch.backend.pitch.PitchRepository;
import com.pitsch.backend.pitch.PitchService;
import com.pitsch.backend.user.SettingsService;
import org.springframework.stereotype.Service;

import static com.pitsch.backend.workflow.WorkflowEngine.FAILED_ACTIONS;
import static com.pitsch.backend.workflow.WorkflowEngine.FOLLOW_UP_ACTIONS;
import static com.pitsch.backend.workflow.WorkflowEngine.NEW_PITCH_ACTIONS;
import static com.pitsch.backend.workflow.WorkflowEngine.REVIEW_ACTIONS;
import static com.pitsch.backend.workflow.WorkflowStatus.AWAITING_USER;
import static com.pitsch.backend.workflow.WorkflowStatus.CLASSIFYING;
import static com.pitsch.backend.workflow.WorkflowStatus.FAILED;
import static com.pitsch.backend.workflow.WorkflowStatus.NOT_PITCH;
import static com.pitsch.backend.workflow.WorkflowStatus.PROCESSING;
import static com.pitsch.backend.workflow.WorkflowStatus.RECEIVED;
import static com.pitsch.backend.workflow.WorkflowStatus.STOPPED;
import static com.pitsch.backend.workflow.WorkflowStatus.WAITING_FOR_APPROVAL;

/**
 * Background workflow steps, executed by job handlers. Each step persists its result before moving on, so a retried
 * job resumes where the previous attempt stopped (stored agent outputs are never recomputed).
 */
@Service
public class WorkflowSteps {

    private final WorkflowStore store;
    private final EmailRepository emails;
    private final EmailAttachmentRepository attachments;
    private final PitchRepository pitches;
    private final PitchService pitchService;
    private final EmailDraftRepository drafts;
    private final UserRepository users;
    private final OrganizationRepository orgs;
    private final SettingsService settings;
    private final IntegrationService integrations;
    private final AgentRunner agents;
    private final AgentInputs inputs;
    private final JobQueue jobs;
    private final NotificationService notifications;
    private final ActivityService activity;
    private final AuditService audit;
    private final UsageService usage;
    private final PitschMetrics metrics;
    private final Json json;
    private final Clock clock;

    private final EntitlementService entitlements;

    public WorkflowSteps(WorkflowStore store, EmailRepository emails, EmailAttachmentRepository attachments,
                         PitchRepository pitches, PitchService pitchService, EmailDraftRepository drafts,
                         UserRepository users, OrganizationRepository orgs, SettingsService settings,
                         IntegrationService integrations, AgentRunner agents, AgentInputs inputs, JobQueue jobs,
                         NotificationService notifications, ActivityService activity, AuditService audit,
                         UsageService usage, EntitlementService entitlements, PitschMetrics metrics, Json json,
                         Clock clock) {
        this.entitlements = entitlements;
        this.store = store;
        this.emails = emails;
        this.attachments = attachments;
        this.pitches = pitches;
        this.pitchService = pitchService;
        this.drafts = drafts;
        this.users = users;
        this.orgs = orgs;
        this.settings = settings;
        this.integrations = integrations;
        this.agents = agents;
        this.inputs = inputs;
        this.jobs = jobs;
        this.notifications = notifications;
        this.activity = activity;
        this.audit = audit;
        this.usage = usage;
        this.metrics = metrics;
        this.json = json;
        this.clock = clock;
    }

    // =====================================================================================
    // 1. Classification
    // =====================================================================================

    public void classify(Long workflowId) {
        Workflow current = store.load(workflowId);
        if (current.getClassificationJson() != null && !CLASSIFYING.equals(current.getStatus())) {
            return;   // already classified (duplicate delivery)
        }
        if (!RECEIVED.equals(current.getStatus()) && !CLASSIFYING.equals(current.getStatus()) && !FAILED.equals(current.getStatus())) {
            return;
        }
        Workflow wf = store.update(workflowId, w -> {
            w.setStatus(CLASSIFYING);
            w.setCurrentStep("CLASSIFYING");
            w.setAvailableActions("STOP");
            w.setError(null);
        });
        Email email = email(wf);
        List<Pitch> candidates = pitchService.followUpCandidates(email);
        AgentResult r = agents.run(wf.getOrganizationId(), workflowId, Agent.EMAIL_CLASSIFIER,
                inputs.classifier(email, attachments.findByEmailIdOrderByIdAsc(email.getId()), candidates));
        if (!r.success()) {
            failOrRetry(wf, "CLASSIFYING", "Email classification failed: " + r.errorMessage(), r.retryable());
            return;
        }
        JsonNode c = r.data();
        boolean isPitch = Json.bool(c, "isPitch");
        Long previousId = parseLong(Json.text(c, "previousPitchId"));
        Pitch linked = previousId == null ? null
                : pitches.findByIdAndOrganizationId(previousId, wf.getOrganizationId()).orElse(null);

        email.setIsPitch(isPitch);
        email.setIsFollowUp(Json.bool(c, "isFollowUp"));
        email.setCategory(Json.text(c, "category"));
        email.setPreviousPitchId(linked == null ? null : linked.getId());
        emails.save(email);
        if (Json.bool(c, "promptInjectionSuspected")) {
            audit.recordAi(wf.getOrganizationId(), AuditAction.PROMPT_INJECTION_FLAGGED, "EMAIL", email.getId(),
                    Map.of("workflowId", workflowId));
        }

        if (!isPitch) {
            boolean unsure = Json.bool(c, "needsHumanReview");
            store.update(workflowId, w -> {
                w.setClassificationJson(json.write(c));
                mergeReview(w, "Classifier", c);
                w.setType("NOT_PITCH");
                w.setStatus(NOT_PITCH);
                w.setCurrentStep("DONE");
                w.setRecommendedAction("STOP");
                w.setAvailableActions(unsure ? "COMPLETE_WORKFLOW" : "");
            });
            activity.log(wf.getOrganizationId(), null, "WORKFLOW", "Not a pitch: \"" + wf.getSummary() + "\" ("
                    + (Json.text(c, "category") == null ? "NOT_PITCH" : Json.text(c, "category")) + ")");
            if (unsure) {
                notifications.notify(wf.getOrganizationId(), wf.getUserId(), NotificationType.POSSIBLE_PITCH, "Possible pitch",
                        "Pitsch wasn't sure whether \"" + wf.getSummary() + "\" is a pitch. You can handle it anyway.",
                        workflowId, "wf:" + workflowId + ":possible");
            }
            return;
        }

        String event;
        Pitch pitch;
        if (Json.bool(c, "isFollowUp") && linked != null) {
            pitch = pitchService.linkFollowUp(linked.getId(), workflowId, email.getThreadId());
            String rec = Json.text(c, "recommendedAction");
            String recommended = rec == null || "ASK_TO_HANDLE".equals(rec) ? "COMPLETE_WORKFLOW" : rec;
            store.update(workflowId, w -> {
                w.setClassificationJson(json.write(c));
                mergeReview(w, "Classifier", c);
                w.setType("FOLLOW_UP");
                w.setPitchId(pitch.getId());
                w.setRecommendedAction(recommended);
                WorkflowStore.waitForUser(w, "AWAITING_DECISION", FOLLOW_UP_ACTIONS, AWAITING_USER);
            });
            notifications.notify(wf.getOrganizationId(), wf.getUserId(), NotificationType.FOLLOW_UP_DETECTED,
                    "Follow-up detected", name(pitch) + " followed up on their pitch.", workflowId, "wf:" + workflowId + ":followup");
            event = "FOLLOW_UP_DETECTED";
        } else {
            // A retried classification must not create a second pitch for the same email.
            pitch = pitches.findFirstByOrganizationIdAndFirstEmailId(wf.getOrganizationId(), email.getId())
                    .orElseGet(() -> pitchService.createFromEmail(wf.getOrganizationId(), wf.getUserId(), workflowId, email, c));
            store.update(workflowId, w -> {
                w.setClassificationJson(json.write(c));
                mergeReview(w, "Classifier", c);
                w.setType("NEW_PITCH");
                w.setPitchId(pitch.getId());
                w.setRecommendedAction("ASK_TO_HANDLE");
                WorkflowStore.waitForUser(w, "AWAITING_DECISION", NEW_PITCH_ACTIONS, AWAITING_USER);
            });
            audit.recordAi(wf.getOrganizationId(), AuditAction.PITCH_CREATED, "PITCH", pitch.getId(), Map.of("workflowId", workflowId));
            notifications.notify(wf.getOrganizationId(), wf.getUserId(), NotificationType.NEW_PITCH, "New pitch detected",
                    name(pitch) + " — would you like Pitsch to handle this pitch?", workflowId, "wf:" + workflowId + ":new");
            event = "PITCH_DETECTED";
        }
        activity.log(wf.getOrganizationId(), null, "WORKFLOW",
                ("PITCH_DETECTED".equals(event) ? "New pitch: " : "Follow-up: ") + name(pitch));
        jobs.enqueue(JobType.WORKFLOW_EVENT_ACTIONS, wf.getOrganizationId(), workflowId,
                Map.of("workflowId", workflowId, "event", event));
    }

    // =====================================================================================
    // 2. Research pipeline: Document -> Research -> Verification -> Analysis
    // =====================================================================================

    public void runPipeline(Long workflowId) {
        Workflow wf = store.load(workflowId);
        if (!PROCESSING.equals(wf.getStatus())) {
            return;   // stopped or already finished
        }
        Long org = wf.getOrganizationId();
        Email email = email(wf);
        Pitch pitch = pitch(wf);
        if (wf.getDocumentJson() == null) {
            jobs.enqueue(JobType.WORKFLOW_EVENT_ACTIONS, org, workflowId,
                    Map.of("workflowId", workflowId, "event", "PROCESSING_STARTED"),
                    "wf-event:" + workflowId + ":PROCESSING_STARTED", Duration.ZERO);
        }

        JsonNode doc = json.read(wf.getDocumentJson());
        if (doc == null) {
            step(workflowId, "DOCUMENT");
            AgentResult r = agents.run(org, workflowId, Agent.DOCUMENT_AGENT,
                    inputs.document(pitch, email, attachments.findByEmailIdOrderByIdAsc(email.getId())));
            if (!r.success()) {
                failOrRetry(wf, "DOCUMENT", "Pitch deck processing failed: " + r.errorMessage(), r.retryable());
                return;
            }
            JsonNode docData = r.data();
            store.update(workflowId, w -> {
                w.setDocumentJson(json.write(docData));
                mergeReview(w, "Document", docData);
            });
            pitch = pitchService.updateFromDocument(pitch.getId(), docData);
            int pages = 0;
            for (JsonNode d : docData.path("documents")) {
                pages += d.path("pageCount").asInt(0);
            }
            usage.record(org, null, UsageMetric.DOCUMENT_PAGES, pages, workflowId);
            doc = docData;
        }

        JsonNode research = json.read(store.load(workflowId).getResearchJson());
        if (research == null && !entitlements.allows(org, UsageMetric.RESEARCH_QUERIES, 1)) {
            store.update(workflowId, w -> addWarning(w,
                    "The workspace's monthly research quota is used up; the brief is based on the pitch only."));
        } else if (research == null) {
            step(workflowId, "RESEARCH");
            AgentResult r = agents.run(org, workflowId, Agent.RESEARCH_AGENT, inputs.research(pitch, doc));
            if (r.success()) {
                JsonNode researchData = r.data();
                store.update(workflowId, w -> {
                    w.setResearchJson(json.write(researchData));
                    mergeReview(w, "Research", researchData);
                });
                usage.record(org, null, UsageMetric.RESEARCH_QUERIES, researchData.path("queriesRun").size(), workflowId);
                research = researchData;
            } else if (r.retryable() && isFirstAttemptWindow(workflowId)) {
                throw new RetryableStepException("RESEARCH", "Research failed: " + r.errorMessage());
            } else {
                String msg = "Research failed (" + r.errorMessage() + "); the brief is based on the pitch only.";
                store.update(workflowId, w -> addWarning(w, msg));
            }
        }

        JsonNode verification = json.read(store.load(workflowId).getVerificationJson());
        if (verification == null && research != null) {
            step(workflowId, "VERIFICATION");
            AgentResult r = agents.run(org, workflowId, Agent.VERIFICATION_AGENT, inputs.verification(pitch, doc, research));
            if (r.success()) {
                JsonNode verData = r.data();
                store.update(workflowId, w -> {
                    w.setVerificationJson(json.write(verData));
                    mergeReview(w, "Verification", verData);
                });
                verification = verData;
            } else {
                String msg = "Claim verification failed (" + r.errorMessage() + "); claims are shown as not checked.";
                store.update(workflowId, w -> addWarning(w, msg));
            }
        }

        step(workflowId, "ANALYSIS");
        AgentResult r = agents.run(org, workflowId, Agent.ANALYSIS_AGENT, inputs.analysis(pitch, doc, research, verification));
        if (!r.success()) {
            failOrRetry(store.load(workflowId), "ANALYSIS", "Building the investment brief failed: " + r.errorMessage(), r.retryable());
            return;
        }
        JsonNode analysis = r.data();
        boolean meetingRequested = Json.bool(json.read(store.load(workflowId).getClassificationJson()), "meetingRequested");
        store.update(workflowId, w -> {
            w.setAnalysisJson(json.write(analysis));
            w.setBriefMarkdown(Json.text(analysis, "markdown"));
            mergeReview(w, "Analysis", analysis);
            WorkflowStore.waitForUser(w, "BRIEF_READY", REVIEW_ACTIONS, WAITING_FOR_APPROVAL);
            w.setRecommendedAction(meetingRequested ? "PLAN_MEETING" : "PLAN_EMAIL_RESPONSE");
        });
        Pitch p = pitchService.recordBrief(pitch.getId(), workflowId, analysis);
        jobs.enqueue(JobType.WORKFLOW_EVENT_ACTIONS, org, workflowId, Map.of("workflowId", workflowId, "event", "BRIEF_READY"));
        int claims = analysis.path("brief").path("claimsMatrix").size();
        notifications.notify(org, wf.getUserId(), NotificationType.ANALYSIS_READY, "Investment brief ready",
                name(p) + ": " + claims + " claims checked. Review the brief and choose a next step.", workflowId,
                "wf:" + workflowId + ":brief");
        activity.log(org, null, "WORKFLOW", "Investment brief ready: " + name(p));
        metrics.workflowFinished("brief_ready", Duration.between(wf.getCreatedAt(), clock.instant()));
    }

    // =====================================================================================
    // 3. Meeting slots (Calendar Agent proposes; it never books)
    // =====================================================================================

    public void planMeeting(Long workflowId, Integer durationMinutes, Long plannerUserId) {
        Workflow wf = store.load(workflowId);
        if (!PROCESSING.equals(wf.getStatus())) {
            return;
        }
        Long calendarUser = plannerUserId != null ? plannerUserId : wf.getUserId();
        Pitch pitch = pitch(wf);
        Email email = email(wf);
        Organization org = orgs.findById(wf.getOrganizationId()).orElseThrow();
        String tz = settings.timezone(calendarUser);
        Instant now = clock.instant();
        List<AgentInputs.BusyInterval> busy = integrations.busy(wf.getOrganizationId(), calendarUser, now, now.plus(Duration.ofDays(21)));
        AgentResult r = agents.run(wf.getOrganizationId(), workflowId, Agent.CALENDAR_AGENT, inputs.calendar(pitch, email,
                json.read(wf.getClassificationJson()), tz, org, busy, durationMinutes));
        if (!r.success()) {
            if (r.retryable()) {
                throw new RetryableStepException("CALENDAR", "Could not find meeting slots: " + r.errorMessage());
            }
            backToReview(workflowId, "Could not find meeting slots: " + r.errorMessage());
            return;
        }
        JsonNode data = r.data();
        int count = data.path("slots").size();
        store.update(workflowId, w -> {
            w.setCalendarJson(json.write(data));
            mergeReview(w, "Calendar", data);
            WorkflowStore.waitForUser(w, "MEETING_SLOTS_READY", REVIEW_ACTIONS, WAITING_FOR_APPROVAL);
            w.setRecommendedAction(null);
        });
        notifications.notify(wf.getOrganizationId(), calendarUser, NotificationType.MEETING_SLOTS_READY, "Meeting slots ready",
                count == 0 ? "No free slot matched the constraints for " + name(pitch) + "."
                        : count + " suggested times for " + name(pitch) + ". Pick one to schedule.", workflowId,
                "wf:" + workflowId + ":slots:" + wf.getVersion());
    }

    // =====================================================================================
    // 4. Email draft (Email Response Agent drafts; it never sends)
    // =====================================================================================

    public void planEmail(Long workflowId, String purpose, String instructions, List<String> requestedQuestions, Long authorUserId) {
        Workflow wf = store.load(workflowId);
        if (!PROCESSING.equals(wf.getStatus())) {
            return;
        }
        Pitch pitch = pitch(wf);
        Email email = email(wf);
        User author = users.findById(authorUserId != null ? authorUserId : wf.getUserId()).orElseThrow();
        JsonNode analysis = json.read(wf.getAnalysisJson());
        JsonNode calendar = json.read(wf.getCalendarJson());
        List<String> questions = requestedQuestions != null && !requestedQuestions.isEmpty()
                ? requestedQuestions : WorkflowEngine.questionsFromBrief(analysis);
        String finalPurpose = "REQUEST_INFO".equals(purpose) && questions.isEmpty()
                && (instructions == null || instructions.isBlank()) ? "ACKNOWLEDGE" : purpose;

        AgentResult r = agents.run(wf.getOrganizationId(), workflowId, Agent.EMAIL_RESPONSE_AGENT, inputs.emailResponse(finalPurpose,
                pitch, email, author, settings.forUser(author.getId()), settings.timezone(author.getId()),
                "REQUEST_INFO".equals(finalPurpose) ? questions : List.of(),
                "PROPOSE_MEETING".equals(finalPurpose) && calendar != null ? calendar.path("slots") : null,
                "CONFIRM_MEETING".equals(finalPurpose) ? wf.getMeetingStart() : null,
                "CONFIRM_MEETING".equals(finalPurpose) ? wf.getMeetingEnd() : null,
                instructions));
        if (!r.success()) {
            if (r.retryable()) {
                throw new RetryableStepException("EMAIL_DRAFTING", "Could not draft the email: " + r.errorMessage());
            }
            backToReview(workflowId, "Could not draft the email: " + r.errorMessage());
            return;
        }
        JsonNode data = r.data();
        EmailDraft draft = new EmailDraft();
        draft.setOrganizationId(wf.getOrganizationId());
        draft.setUserId(author.getId());
        draft.setWorkflowId(workflowId);
        draft.setPitchId(pitch.getId());
        // The recipient is decided by the backend (founder of record), never by model output: a prompt-injected email
        // must not be able to redirect the reply. A disagreeing suggestion is surfaced for review instead.
        String recipient = pitch.getFounderEmail() != null ? pitch.getFounderEmail() : email.getSender();
        String suggested = Json.text(data, "recipient");
        List<String> reasons = new java.util.ArrayList<>(Json.strings(data, "reviewReasons"));
        if (suggested != null && !suggested.equalsIgnoreCase(recipient)) {
            reasons.add("The AI suggested a different recipient (" + Json.truncate(suggested, 120)
                    + "); the draft is addressed to the founder of record.");
        }
        draft.setRecipient(recipient);
        draft.setRecipientName(pitch.getFounderName() != null ? pitch.getFounderName() : Json.text(data, "recipientName"));
        draft.setSubject(Json.truncate(Json.text(data, "subject"), 1000));
        draft.setBody(data.path("body").asText(""));
        draft.setPurpose(Json.text(data, "purpose"));
        draft.setNeedsHumanReview(Json.bool(data, "needsHumanReview") || reasons.size() > Json.strings(data, "reviewReasons").size());
        draft.setReviewReasonsJson(json.write(reasons));
        drafts.save(draft);
        store.update(workflowId, w -> WorkflowStore.waitForUser(w, "EMAIL_DRAFT_READY", REVIEW_ACTIONS, WAITING_FOR_APPROVAL));
        notifications.notify(wf.getOrganizationId(), author.getId(), NotificationType.EMAIL_DRAFT_READY, "Email draft ready",
                "Review the draft to " + draft.getRecipient() + " before sending.", workflowId, "draft:" + draft.getId() + ":ready");
    }

    // =====================================================================================
    // Failure handling
    // =====================================================================================

    /** Called when a step's job gives up (dead-lettered), or for non-retryable agent failures. */
    public void fail(Long workflowId, String step, String message) {
        Workflow wf;
        try {
            wf = store.update(workflowId, false, w -> {
                if (STOPPED.equals(w.getStatus()) || WorkflowStatus.isFinal(w.getStatus())) {
                    return;
                }
                w.setStatus(FAILED);
                w.setCurrentStep(step);
                w.setError(Json.truncate(message, 2000));
                w.setAvailableActions(Json.joinCsv(FAILED_ACTIONS));
            });
        } catch (ApiException e) {
            return;
        }
        if (!FAILED.equals(wf.getStatus())) {
            return;
        }
        notifications.notify(wf.getOrganizationId(), wf.getUserId(), NotificationType.WORKFLOW_FAILED, "Workflow needs attention",
                message, workflowId, "wf:" + workflowId + ":failed:" + wf.getVersion());
        activity.log(wf.getOrganizationId(), null, "WORKFLOW", "Workflow failed at " + step + ": " + message);
        audit.recordAi(wf.getOrganizationId(), AuditAction.WORKFLOW_FAILED, "WORKFLOW", workflowId, Map.of("step", step));
        metrics.workflowFinished("failed", Duration.between(wf.getCreatedAt(), clock.instant()));
    }

    /** Meeting/email planning failures don't kill the workflow: go back to review with the error shown. */
    public void backToReview(Long workflowId, String message) {
        Workflow wf = store.update(workflowId, w -> {
            if (!PROCESSING.equals(w.getStatus())) {
                return;
            }
            WorkflowStore.waitForUser(w, "REVIEW", REVIEW_ACTIONS, WAITING_FOR_APPROVAL);
            w.setError(Json.truncate(message, 2000));
        });
        notifications.notify(wf.getOrganizationId(), wf.getUserId(), NotificationType.WORKFLOW_FAILED, "Action failed", message,
                workflowId, "wf:" + workflowId + ":review:" + wf.getVersion());
    }

    private void failOrRetry(Workflow wf, String step, String message, boolean retryable) {
        if (retryable) {
            throw new RetryableStepException(step, message);
        }
        fail(wf.getId(), step, message);
    }

    /** Research is optional: retry it only while the workflow is young; afterwards continue without it. */
    private boolean isFirstAttemptWindow(Long workflowId) {
        Workflow wf = store.load(workflowId);
        return wf.getLastTransitionAt() == null || wf.getLastTransitionAt().isAfter(clock.instant().minus(Duration.ofMinutes(10)));
    }

    // =====================================================================================
    // Helpers
    // =====================================================================================

    private void step(Long workflowId, String step) {
        store.update(workflowId, w -> {
            w.setStatus(PROCESSING);
            w.setCurrentStep(step);
        });
    }

    private void mergeReview(Workflow w, String source, JsonNode data) {
        if (data == null) {
            return;
        }
        List<String> reasons = new ArrayList<>(list(w.getReviewReasonsJson()));
        for (String r : Json.strings(data, "reviewReasons")) {
            String line = source + ": " + r;
            if (!reasons.contains(line)) {
                reasons.add(line);
            }
        }
        List<String> warnings = new ArrayList<>(list(w.getWarningsJson()));
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
        List<String> warnings = new ArrayList<>(list(w.getWarningsJson()));
        if (!warnings.contains(warning)) {
            warnings.add(warning);
        }
        w.setWarningsJson(json.write(warnings));
    }

    private List<String> list(String jsonArray) {
        List<String> out = new ArrayList<>();
        JsonNode n = json.read(jsonArray);
        if (n != null && n.isArray()) {
            n.forEach(v -> out.add(v.asText()));
        }
        return out;
    }

    private Email email(Workflow wf) {
        return emails.findById(wf.getEmailId()).orElseThrow(() -> ApiException.notFound("Email"));
    }

    private Pitch pitch(Workflow wf) {
        if (wf.getPitchId() == null) {
            throw ApiException.conflict("This workflow isn't linked to a pitch yet.");
        }
        return pitches.findById(wf.getPitchId()).orElseThrow(() -> ApiException.notFound("Pitch"));
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
}
