package com.pitsch.backend.workflow;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pitsch.backend.activity.ActivityService;
import com.pitsch.backend.approval.ApprovalService;
import com.pitsch.backend.approval.ApprovalType;
import com.pitsch.backend.audit.AuditAction;
import com.pitsch.backend.audit.AuditService;
import com.pitsch.backend.auth.AuthPrincipal;
import com.pitsch.backend.auth.Permission;
import com.pitsch.backend.billing.EntitlementService;
import com.pitsch.backend.billing.UsageMetric;
import com.pitsch.backend.billing.UsageService;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.ErrorCode;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.email.Email;
import com.pitsch.backend.email.EmailRepository;
import com.pitsch.backend.event.CalendarEvent;
import com.pitsch.backend.event.CalendarEventRepository;
import com.pitsch.backend.event.EventController;
import com.pitsch.backend.integration.Integration;
import com.pitsch.backend.integration.IntegrationService;
import com.pitsch.backend.integration.ProviderRegistry;
import com.pitsch.backend.jobs.Job;
import com.pitsch.backend.jobs.JobQueue;
import com.pitsch.backend.jobs.JobRepository;
import com.pitsch.backend.jobs.JobType;
import com.pitsch.backend.notification.NotificationService;
import com.pitsch.backend.notification.NotificationType;
import com.pitsch.backend.pitch.Pitch;
import com.pitsch.backend.pitch.PitchRepository;
import com.pitsch.backend.pitch.PitchService;
import com.pitsch.backend.user.SettingsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static com.pitsch.backend.workflow.WorkflowStatus.AWAITING_USER;
import static com.pitsch.backend.workflow.WorkflowStatus.CLASSIFYING;
import static com.pitsch.backend.workflow.WorkflowStatus.COMPLETED;
import static com.pitsch.backend.workflow.WorkflowStatus.FAILED;
import static com.pitsch.backend.workflow.WorkflowStatus.NOT_PITCH;
import static com.pitsch.backend.workflow.WorkflowStatus.RECEIVED;
import static com.pitsch.backend.workflow.WorkflowStatus.STOPPED;
import static com.pitsch.backend.workflow.WorkflowStatus.WAITING_FOR_APPROVAL;

/**
 * User-facing workflow orchestration. The AI agents only interpret information; this class turns user decisions into
 * validated state transitions, queued background work and explicit approvals. Every compound change is one
 * transaction (state + approval + job), protected by optimistic locking.
 * <p>
 * Background steps live in {@link WorkflowSteps}; execution of approved external actions in
 * {@link com.pitsch.backend.actions.ActionService}.
 */
@Service
public class WorkflowEngine {

    public static final List<String> NEW_PITCH_ACTIONS = List.of("COMPLETE_WORKFLOW", "STOP");
    public static final List<String> FOLLOW_UP_ACTIONS = List.of("COMPLETE_WORKFLOW", "STOP", "PLAN_MEETING", "PLAN_EMAIL_RESPONSE");
    public static final List<String> REVIEW_ACTIONS = List.of("PLAN_MEETING", "PLAN_EMAIL_RESPONSE", "COMPLETE_WORKFLOW", "STOP");
    public static final List<String> FAILED_ACTIONS = List.of("RETRY", "STOP");
    static final Set<String> EMAIL_PURPOSES = Set.of("ACKNOWLEDGE", "REQUEST_INFO", "PROPOSE_MEETING",
            "CONFIRM_MEETING", "DECLINE", "GENERAL_REPLY");
    private static final Duration MIN_MEETING_NOTICE = Duration.ofMinutes(5);

    private final WorkflowRepository workflows;
    private final EmailRepository emails;
    private final PitchRepository pitches;
    private final PitchService pitchService;
    private final EmailDraftRepository drafts;
    private final CalendarEventRepository events;
    private final JobQueue jobs;
    private final JobRepository jobRepo;
    private final ApprovalService approvals;
    private final IntegrationService integrations;
    private final ProviderRegistry providers;
    private final EntitlementService entitlements;
    private final UsageService usage;
    private final SettingsService settings;
    private final NotificationService notifications;
    private final ActivityService activity;
    private final AuditService audit;
    private final Json json;
    private final Clock clock;
    private final TransactionTemplate tx;

    public WorkflowEngine(WorkflowRepository workflows, EmailRepository emails, PitchRepository pitches,
                          PitchService pitchService, EmailDraftRepository drafts, CalendarEventRepository events,
                          JobQueue jobs, JobRepository jobRepo, ApprovalService approvals,
                          IntegrationService integrations, ProviderRegistry providers, EntitlementService entitlements,
                          UsageService usage, SettingsService settings, NotificationService notifications,
                          ActivityService activity, AuditService audit, Json json, Clock clock,
                          PlatformTransactionManager txManager) {
        this.workflows = workflows;
        this.emails = emails;
        this.pitches = pitches;
        this.pitchService = pitchService;
        this.drafts = drafts;
        this.events = events;
        this.jobs = jobs;
        this.jobRepo = jobRepo;
        this.approvals = approvals;
        this.integrations = integrations;
        this.providers = providers;
        this.entitlements = entitlements;
        this.usage = usage;
        this.settings = settings;
        this.notifications = notifications;
        this.activity = activity;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    // =====================================================================================
    // Creation (inside the ingestion transaction)
    // =====================================================================================

    /** Creates the email's workflow and queues classification (or parks it if the AI quota is used up). */
    public Workflow createForEmail(Email email, boolean quotaAvailable) {
        Workflow wf = new Workflow();
        wf.setOrganizationId(email.getOrganizationId());
        wf.setUserId(email.getUserId());
        wf.setEmailId(email.getId());
        wf.setSummary(Json.truncate(email.getSubject() != null && !email.getSubject().isBlank()
                ? email.getSubject() : "Email from " + email.getSender(), 1000));
        wf.setLastTransitionAt(clock.instant());
        if (!quotaAvailable) {
            wf.setStatus(FAILED);
            wf.setCurrentStep("QUOTA");
            wf.setAvailableActions(Json.joinCsv(FAILED_ACTIONS));
            wf.setError("Your plan's monthly AI workflow limit is reached. Retry after upgrading or next month.");
            wf = workflows.save(wf);
            notifications.notify(wf.getOrganizationId(), wf.getUserId(), NotificationType.QUOTA_REACHED,
                    "AI quota reached", "A new email could not be analysed because the monthly limit is reached.",
                    wf.getId(), "quota:" + usage.currentPeriod());
            return wf;
        }
        wf.setStatus(RECEIVED);
        wf.setCurrentStep("RECEIVED");
        wf.setAvailableActions("STOP");
        wf = workflows.save(wf);
        usage.record(wf.getOrganizationId(), wf.getUserId(), UsageMetric.AI_WORKFLOWS, 1, wf.getId());
        audit.record(wf.getOrganizationId(), wf.getUserId(), AuditAction.WORKFLOW_STARTED, "WORKFLOW", wf.getId(), null);
        jobs.enqueue(JobType.CLASSIFY_EMAIL, wf.getOrganizationId(), wf.getId(), Map.of("workflowId", wf.getId()));
        return wf;
    }

    // =====================================================================================
    // User actions
    // =====================================================================================

    public Workflow applyAction(AuthPrincipal principal, Long workflowId, ActionRequest req) {
        principal.require(Permission.WORKFLOW_RUN);
        if (req == null || req.action() == null) {
            throw ApiException.badRequest("action is required");
        }
        WorkflowAction action;
        try {
            action = WorkflowAction.valueOf(req.action().trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown action '" + Json.truncate(req.action(), 40)
                    + "'. Use exact values like PLAN_MEETING.");
        }
        tx.executeWithoutResult(s -> {
            Workflow wf = find(principal, workflowId);
            List<String> available = Json.splitCsv(wf.getAvailableActions());
            if (!available.contains(action.name())) {
                throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Action " + action + " is not available while the workflow is "
                        + wf.getStatus() + ". Available: " + (available.isEmpty() ? "none" : String.join(", ", available)));
            }
            switch (action) {
                case STOP -> stop(principal, wf);
                case RETRY -> retry(wf);
                case COMPLETE_WORKFLOW -> {
                    if (NOT_PITCH.equals(wf.getStatus())) {
                        convertToPitch(principal, wf);
                        startPipeline(wf);
                    } else if (closesOnComplete(wf)) {
                        complete(principal, wf);
                    } else {
                        startPipeline(wf);
                    }
                }
                case PLAN_MEETING -> {
                    requirePitch(wf);
                    Integer duration = req.durationMinutes();
                    transition(wf, w -> WorkflowStore.processing(w, "CALENDAR"));
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("workflowId", wf.getId());
                    if (duration != null) {
                        payload.put("durationMinutes", duration);
                    }
                    payload.put("plannerUserId", principal.userId());
                    jobs.enqueue(JobType.PLAN_MEETING, wf.getOrganizationId(), wf.getId(), payload);
                }
                case PLAN_EMAIL_RESPONSE -> {
                    requirePitch(wf);
                    String purpose = choosePurpose(wf, req);
                    transition(wf, w -> WorkflowStore.processing(w, "EMAIL_DRAFTING"));
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("workflowId", wf.getId());
                    payload.put("purpose", purpose);
                    payload.put("authorUserId", principal.userId());
                    if (req.instructions() != null && !req.instructions().isBlank()) {
                        payload.put("instructions", Json.truncate(req.instructions(), 2000));
                    }
                    if (req.questions() != null && !req.questions().isEmpty()) {
                        payload.put("questions", req.questions().stream().limit(10)
                                .map(q -> Json.truncate(q, 500)).toList());
                    }
                    jobs.enqueue(JobType.DRAFT_EMAIL, wf.getOrganizationId(), wf.getId(), payload);
                }
                default -> throw ApiException.badRequest("Unsupported action " + action);
            }
            audit.record(wf.getOrganizationId(), principal.userId(), AuditAction.WORKFLOW_ACTION, "WORKFLOW", wf.getId(),
                    Map.of("action", action.name()));
        });
        return load(workflowId);
    }

    /**
     * The investor picked a slot. The backend re-checks availability, records the approval and queues execution;
     * the calendar event is created by ActionService — never by an agent.
     */
    public Workflow approveMeeting(AuthPrincipal principal, Long workflowId, String start, String end, String title) {
        principal.require(Permission.ACTION_APPROVE);
        tx.executeWithoutResult(s -> {
            Workflow wf = find(principal, workflowId);
            if (!WAITING_FOR_APPROVAL.equals(wf.getStatus()) && !AWAITING_USER.equals(wf.getStatus())) {
                throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "A meeting can only be scheduled while the workflow "
                        + "is waiting for you (status: " + wf.getStatus() + ").");
            }
            requirePitch(wf);
            if (!providers.demo()) {
                integrations.require(wf.getOrganizationId(), principal.userId(), Integration.CALENDAR);
            }
            String tz = settings.timezone(principal.userId());
            ZoneId zone = ZoneId.of(tz);
            Instant s0 = EventController.parseInstant(start, zone, "start");
            Instant e0 = EventController.parseInstant(end, zone, "end");
            if (!e0.isAfter(s0)) {
                throw ApiException.badRequest("end must be after start");
            }
            if (Duration.between(s0, e0).toMinutes() > 480) {
                throw ApiException.badRequest("Meetings can be at most 8 hours long.");
            }
            if (s0.isBefore(clock.instant().plus(MIN_MEETING_NOTICE))) {
                throw ApiException.badRequest("Pick a time in the future.");
            }
            boolean busy = integrations.busy(wf.getOrganizationId(), principal.userId(), s0, e0).stream()
                    .anyMatch(b -> b.start().isBefore(e0) && b.end().isAfter(s0));
            if (busy) {
                throw ApiException.conflict("That time is no longer free in your calendar. Plan the meeting again to get fresh slots.");
            }
            Pitch pitch = pitch(wf);
            ObjectNode payload = json.obj();
            payload.put("workflowId", wf.getId());
            payload.put("pitchId", pitch.getId());
            payload.put("calendarOwnerUserId", principal.userId());
            payload.put("title", title != null && !title.isBlank() ? Json.truncate(title.trim(), 200)
                    : "Pitch meeting: " + (pitch.getCompanyName() == null ? "startup" : pitch.getCompanyName()));
            payload.put("description", "Pitch meeting with " + (pitch.getCompanyName() == null ? "the founder" : pitch.getCompanyName())
                    + ".\nScheduled via Pitsch.");
            payload.put("start", s0.toString());
            payload.put("end", e0.toString());
            payload.put("timezone", tz);
            var attendees = payload.putArray("attendees");
            if (pitch.getFounderEmail() != null) {
                attendees.add(pitch.getFounderEmail());
            }
            payload.put("addVideoConference", true);
            approvals.approveNow(principal, wf.getId(), pitch.getId(), ApprovalType.CREATE_MEETING,
                    "Create meeting with " + (pitch.getFounderEmail() == null ? "founder" : pitch.getFounderEmail()) + " at " + s0,
                    payload, "meeting:" + wf.getId() + ":" + s0.getEpochSecond() + ":" + e0.getEpochSecond());
            transition(wf, w -> WorkflowStore.processing(w, "CREATING_MEETING"));
        });
        return load(workflowId);
    }

    /** Cancels the workflow's scheduled meeting (approval = this request). */
    public Workflow cancelMeeting(AuthPrincipal principal, Long workflowId) {
        principal.require(Permission.ACTION_APPROVE);
        tx.executeWithoutResult(s -> {
            Workflow wf = find(principal, workflowId);
            if (wf.getMeetingEventId() == null) {
                throw ApiException.conflict("This workflow has no scheduled meeting.");
            }
            if (WorkflowStatus.isFinal(wf.getStatus()) || WorkflowStatus.ACTIVE.contains(wf.getStatus())) {
                throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "The meeting cannot be cancelled right now.");
            }
            CalendarEvent event = events.findByIdAndOrganizationId(wf.getMeetingEventId(), wf.getOrganizationId())
                    .orElseThrow(() -> ApiException.notFound("Meeting"));
            ObjectNode payload = json.obj();
            payload.put("workflowId", wf.getId());
            payload.put("eventId", event.getId());
            approvals.approveNow(principal, wf.getId(), wf.getPitchId(), ApprovalType.CANCEL_MEETING,
                    "Cancel meeting \"" + event.getTitle() + "\"", payload, "meeting-cancel:" + event.getId());
            transition(wf, w -> WorkflowStore.processing(w, "CANCELLING_MEETING"));
        });
        return load(workflowId);
    }

    /** The investor clicked Send: the (possibly edited) draft is approved and queued for sending. */
    public Workflow sendDraft(AuthPrincipal principal, Long draftId, String subject, String body) {
        principal.require(Permission.ACTION_APPROVE);
        Long[] workflowId = new Long[1];
        tx.executeWithoutResult(s -> {
            EmailDraft draft = drafts.findByIdAndOrganizationId(draftId, principal.orgId())
                    .orElseThrow(() -> ApiException.notFound("Draft"));
            if (!"DRAFT".equals(draft.getStatus()) && !"FAILED".equals(draft.getStatus())) {
                throw ApiException.conflict("This draft was already " + draft.getStatus().toLowerCase(Locale.ROOT) + ".");
            }
            if (subject != null && !subject.isBlank()) {
                draft.setSubject(Json.truncate(subject.trim().replaceAll("[\\r\\n]+", " "), 1000));
            }
            if (body != null && !body.isBlank()) {
                draft.setBody(Json.truncate(body, 50_000));
            }
            if (draft.getRecipient() == null || !draft.getRecipient().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                throw ApiException.badRequest("The draft has no valid recipient.");
            }
            if (draft.getBody() == null || draft.getBody().isBlank()) {
                throw ApiException.badRequest("The email body is empty.");
            }
            Workflow wf = find(principal, draft.getWorkflowId());
            workflowId[0] = wf.getId();
            if (WorkflowStatus.isFinal(wf.getStatus()) || WorkflowStatus.ACTIVE.contains(wf.getStatus())) {
                throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "The workflow is " + wf.getStatus() + ".");
            }
            if (!providers.demo()) {
                integrations.require(wf.getOrganizationId(), principal.userId(), Integration.GMAIL);
            }
            Email email = email(wf);
            ObjectNode payload = json.obj();
            payload.put("draftId", draft.getId());
            payload.put("workflowId", wf.getId());
            payload.put("senderUserId", principal.userId());
            payload.put("to", draft.getRecipient());
            payload.put("subject", draft.getSubject() == null ? "" : draft.getSubject());
            payload.put("body", draft.getBody());
            if (email.getThreadId() != null && Email.Source.GMAIL.name().equals(email.getSource())) {
                payload.put("threadId", email.getThreadId());
            }
            if (email.getMessageId() != null) {
                payload.put("inReplyTo", email.getMessageId());
            }
            // The draft version changes on every save, so a retry after a confirmed failure gets a fresh key while
            // a double-click is refused above (status is already SENDING).
            String key = "draft:" + draft.getId() + ":send:v" + draft.getVersion();
            var approval = approvals.approveNow(principal, wf.getId(), wf.getPitchId(), ApprovalType.SEND_EMAIL,
                    "Send \"" + Json.truncate(draft.getSubject(), 120) + "\" to " + draft.getRecipient(), payload, key);
            draft.setStatus("SENDING");
            draft.setApprovalId(approval.getId());
            draft.setIdempotencyKey(key);
            draft.setFailureReason(null);
            drafts.save(draft);
            transition(wf, w -> WorkflowStore.processing(w, "SENDING_EMAIL"));
        });
        return load(workflowId[0]);
    }

    public Workflow cancelDraft(AuthPrincipal principal, Long draftId) {
        principal.require(Permission.WORKFLOW_RUN);
        Long[] workflowId = new Long[1];
        tx.executeWithoutResult(s -> {
            EmailDraft draft = drafts.findByIdAndOrganizationId(draftId, principal.orgId())
                    .orElseThrow(() -> ApiException.notFound("Draft"));
            workflowId[0] = draft.getWorkflowId();
            if ("DRAFT".equals(draft.getStatus()) || "FAILED".equals(draft.getStatus())) {
                draft.setStatus("CANCELLED");
                drafts.save(draft);
            }
            Workflow wf = find(principal, draft.getWorkflowId());
            if (!WorkflowStatus.isFinal(wf.getStatus()) && !WorkflowStatus.ACTIVE.contains(wf.getStatus())) {
                wf.setCurrentStep("EMAIL_DRAFT_CANCELLED");
                workflows.save(wf);
            }
        });
        return load(workflowId[0]);
    }

    public EmailDraft updateDraft(AuthPrincipal principal, Long draftId, String subject, String body) {
        principal.require(Permission.WORKFLOW_RUN);
        return tx.execute(s -> {
            EmailDraft draft = drafts.findByIdAndOrganizationId(draftId, principal.orgId())
                    .orElseThrow(() -> ApiException.notFound("Draft"));
            if (!"DRAFT".equals(draft.getStatus())) {
                throw ApiException.conflict("Only drafts that haven't been sent can be edited.");
            }
            if (subject != null) {
                draft.setSubject(Json.truncate(subject.replaceAll("[\\r\\n]+", " "), 1000));
            }
            if (body != null) {
                draft.setBody(Json.truncate(body, 50_000));
            }
            return drafts.save(draft);
        });
    }

    // =====================================================================================
    // Transitions (called inside the caller's transaction)
    // =====================================================================================

    private void stop(AuthPrincipal principal, Workflow wf) {
        transition(wf, w -> {
            w.setStatus(STOPPED);
            w.setCurrentStep("STOPPED");
            w.setAvailableActions("");
            w.setRecommendedAction(null);
            w.setCompletedAt(clock.instant());
        });
        for (Job j : jobRepo.findByWorkflowIdAndStatusIn(wf.getId(), List.of(Job.Status.QUEUED.name()))) {
            j.setStatus(Job.Status.CANCELLED.name());
            j.setCompletedAt(clock.instant());
            jobRepo.save(j);
        }
        if (wf.getPitchId() != null) {
            pitchService.setStatus(wf.getPitchId(), "STOPPED");
        }
        activity.log(wf.getOrganizationId(), principal.userId(), "WORKFLOW", "Workflow stopped: " + wf.getSummary());
        audit.record(wf.getOrganizationId(), principal.userId(), AuditAction.WORKFLOW_STOPPED, "WORKFLOW", wf.getId(), null);
        jobs.enqueue(JobType.WORKFLOW_EVENT_ACTIONS, wf.getOrganizationId(), wf.getId(),
                Map.of("workflowId", wf.getId(), "event", "WORKFLOW_STOPPED"));
    }

    private void complete(AuthPrincipal principal, Workflow wf) {
        transition(wf, w -> {
            w.setStatus(COMPLETED);
            w.setCurrentStep("DONE");
            w.setAvailableActions("");
            w.setRecommendedAction(null);
            w.setCompletedAt(clock.instant());
        });
        if (wf.getPitchId() != null) {
            pitchService.setStatus(wf.getPitchId(), "COMPLETED");
        }
        jobs.enqueue(JobType.WORKFLOW_EVENT_ACTIONS, wf.getOrganizationId(), wf.getId(),
                Map.of("workflowId", wf.getId(), "event", "WORKFLOW_COMPLETED"));
        notifications.notify(wf.getOrganizationId(), wf.getUserId(), NotificationType.WORKFLOW_COMPLETED, "Workflow completed",
                wf.getSummary(), wf.getId(), "wf:" + wf.getId() + ":completed");
        activity.log(wf.getOrganizationId(), principal.userId(), "WORKFLOW", "Workflow completed: " + wf.getSummary());
        audit.record(wf.getOrganizationId(), principal.userId(), AuditAction.WORKFLOW_COMPLETED, "WORKFLOW", wf.getId(), null);
    }

    private void retry(Workflow wf) {
        entitlements.require(wf.getOrganizationId(), UsageMetric.AI_WORKFLOWS, wf.getClassificationJson() == null ? 1 : 0);
        if (wf.getClassificationJson() == null) {
            transition(wf, w -> {
                w.setStatus(CLASSIFYING);
                w.setCurrentStep("CLASSIFYING");
                w.setAvailableActions("STOP");
                w.setError(null);
            });
            if ("QUOTA".equals(wf.getCurrentStep())) {
                usage.record(wf.getOrganizationId(), wf.getUserId(), UsageMetric.AI_WORKFLOWS, 1, wf.getId());
            }
            jobs.enqueue(JobType.CLASSIFY_EMAIL, wf.getOrganizationId(), wf.getId(), Map.of("workflowId", wf.getId()));
        } else {
            startPipeline(wf);
        }
        audit.record(wf.getOrganizationId(), null, AuditAction.WORKFLOW_RETRIED, "WORKFLOW", wf.getId(), null);
    }

    private void startPipeline(Workflow wf) {
        if (wf.getAnalysisJson() == null && wf.getDocumentJson() == null) {
            entitlements.require(wf.getOrganizationId(), UsageMetric.PITCHES_PROCESSED, 1);
            usage.record(wf.getOrganizationId(), wf.getUserId(), UsageMetric.PITCHES_PROCESSED, 1, wf.getId());
        }
        transition(wf, w -> WorkflowStore.processing(w, "DOCUMENT"));
        if (wf.getPitchId() != null) {
            pitchService.setStatus(wf.getPitchId(), "PROCESSING");
        }
        jobs.enqueue(JobType.RUN_PIPELINE, wf.getOrganizationId(), wf.getId(), Map.of("workflowId", wf.getId()));
    }

    /** COMPLETE_WORKFLOW closes the workflow once a brief exists, or when the founder ended the conversation. */
    private boolean closesOnComplete(Workflow wf) {
        if (wf.getAnalysisJson() != null) {
            return true;
        }
        return "FOLLOW_UP".equals(wf.getType()) && Json.bool(json.read(wf.getClassificationJson()), "workflowClosed");
    }

    private void convertToPitch(AuthPrincipal principal, Workflow wf) {
        Email email = email(wf);
        Pitch pitch = pitchService.createFromEmail(wf.getOrganizationId(), wf.getUserId(), wf.getId(), email,
                json.read(wf.getClassificationJson()));
        email.setIsPitch(true);
        emails.save(email);
        wf.setType("NEW_PITCH");
        wf.setPitchId(pitch.getId());
        activity.log(wf.getOrganizationId(), principal.userId(), "WORKFLOW", "Treated as a pitch: " + wf.getSummary());
        audit.record(wf.getOrganizationId(), principal.userId(), AuditAction.PITCH_CREATED, "PITCH", pitch.getId(),
                Map.of("from", "not_pitch_override"));
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

    /** Applies a change, validates the state transition and saves (version-checked) in the current transaction. */
    private void transition(Workflow wf, java.util.function.Consumer<Workflow> change) {
        String before = wf.getStatus();
        change.accept(wf);
        if (!before.equals(wf.getStatus())) {
            WorkflowStatus.requireTransition(before, wf.getStatus());
            wf.setLastTransitionAt(clock.instant());
        }
        workflows.save(wf);
    }

    // =====================================================================================
    // Lookups
    // =====================================================================================

    public Workflow load(Long id) {
        return workflows.findById(id).orElseThrow(() -> ApiException.notFound("Workflow"));
    }

    private Workflow find(AuthPrincipal principal, Long id) {
        return workflows.findByIdAndOrganizationId(id, principal.orgId()).orElseThrow(() -> ApiException.notFound("Workflow"));
    }

    private void requirePitch(Workflow wf) {
        if (wf.getPitchId() == null) {
            throw ApiException.conflict("This workflow isn't linked to a pitch yet.");
        }
    }

    private Email email(Workflow wf) {
        return emails.findById(wf.getEmailId()).orElseThrow(() -> ApiException.notFound("Email"));
    }

    private Pitch pitch(Workflow wf) {
        return pitches.findById(wf.getPitchId()).orElseThrow(() -> ApiException.notFound("Pitch"));
    }

    /** Founder-friendly questions for a REQUEST_INFO email, taken from the brief's open questions. */
    public static List<String> questionsFromBrief(JsonNode analysis) {
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
        java.util.Set<String> all = new java.util.LinkedHashSet<>(missing);
        all.addAll(other);
        all.addAll(claims);
        List<String> unique = new ArrayList<>(all);
        return new ArrayList<>(unique.subList(0, Math.min(3, unique.size())));
    }
}
