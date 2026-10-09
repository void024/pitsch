package com.pitsch.backend.actions;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.pitsch.backend.activity.ActivityService;
import com.pitsch.backend.approval.Approval;
import com.pitsch.backend.approval.ApprovalRepository;
import com.pitsch.backend.approval.ApprovalService;
import com.pitsch.backend.audit.AuditAction;
import com.pitsch.backend.audit.AuditService;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.email.Email;
import com.pitsch.backend.email.EmailRepository;
import com.pitsch.backend.event.CalendarEvent;
import com.pitsch.backend.event.CalendarEventRepository;
import com.pitsch.backend.idempotency.ExternalOperationService;
import com.pitsch.backend.integration.Integration;
import com.pitsch.backend.integration.IntegrationConnection;
import com.pitsch.backend.integration.IntegrationConnectionRepository;
import com.pitsch.backend.integration.IntegrationException;
import com.pitsch.backend.integration.IntegrationService;
import com.pitsch.backend.integration.ProviderRegistry;
import com.pitsch.backend.integration.provider.CalendarProvider;
import com.pitsch.backend.integration.provider.EmailProvider;
import com.pitsch.backend.integration.sheets.PipelineSyncService;
import com.pitsch.backend.jobs.JobQueue;
import com.pitsch.backend.jobs.JobType;
import com.pitsch.backend.notification.NotificationService;
import com.pitsch.backend.notification.NotificationType;
import com.pitsch.backend.pitch.PitchService;
import com.pitsch.backend.workflow.EmailDraft;
import com.pitsch.backend.workflow.EmailDraftRepository;
import com.pitsch.backend.workflow.Workflow;
import com.pitsch.backend.workflow.WorkflowEngine;
import com.pitsch.backend.workflow.WorkflowStatus;
import com.pitsch.backend.workflow.WorkflowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Executes APPROVED actions against Gmail / Calendar / Sheets. Every external call is guarded by the external
 * operation ledger and a provider-level idempotency key (Gmail Message-ID, deterministic Calendar event ID, Sheets
 * key column), so a retried job never duplicates a side effect. In demo mode the mock providers are used and every
 * result is labelled as simulated.
 */
@Service
public class ActionService {

    private static final Logger log = LoggerFactory.getLogger(ActionService.class);

    private final ApprovalRepository approvals;
    private final ApprovalService approvalService;
    private final EmailDraftRepository drafts;
    private final EmailRepository emails;
    private final CalendarEventRepository events;
    private final IntegrationService integrations;
    private final IntegrationConnectionRepository connections;
    private final ProviderRegistry providers;
    private final ExternalOperationService ops;
    private final PipelineSyncService sheets;
    private final WorkflowStore store;
    private final PitchService pitches;
    private final JobQueue jobs;
    private final NotificationService notifications;
    private final ActivityService activity;
    private final AuditService audit;
    private final Json json;
    private final Clock clock;

    public ActionService(ApprovalRepository approvals, ApprovalService approvalService, EmailDraftRepository drafts,
                         EmailRepository emails, CalendarEventRepository events, IntegrationService integrations,
                         IntegrationConnectionRepository connections, ProviderRegistry providers,
                         ExternalOperationService ops, PipelineSyncService sheets, WorkflowStore store,
                         PitchService pitches, JobQueue jobs, NotificationService notifications,
                         ActivityService activity, AuditService audit, Json json, Clock clock) {
        this.approvals = approvals;
        this.approvalService = approvalService;
        this.drafts = drafts;
        this.emails = emails;
        this.events = events;
        this.integrations = integrations;
        this.connections = connections;
        this.providers = providers;
        this.ops = ops;
        this.sheets = sheets;
        this.store = store;
        this.pitches = pitches;
        this.jobs = jobs;
        this.notifications = notifications;
        this.activity = activity;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    // =====================================================================================
    // Approval execution (EXECUTE_APPROVAL job)
    // =====================================================================================

    public void executeApproval(Long approvalId) {
        Approval a = approvals.findById(approvalId).orElse(null);
        if (a == null || Approval.Status.EXECUTED.name().equals(a.getStatus())) {
            return;
        }
        if (!Approval.Status.APPROVED.name().equals(a.getStatus()) && !Approval.Status.EXECUTING.name().equals(a.getStatus())) {
            log.warn("Approval {} is {}, not executing", approvalId, a.getStatus());
            return;
        }
        if (a.getDecidedByUserId() == null) {
            throw new IllegalStateException("Approval " + approvalId + " has no human decision");   // defence in depth
        }
        a.setStatus(Approval.Status.EXECUTING.name());
        a = approvals.save(a);
        JsonNode payload = approvalService.verifiedPayload(a);
        switch (a.type()) {
            case SEND_EMAIL -> sendEmail(a, payload);
            case CREATE_MEETING -> createMeeting(a, payload);
            case CANCEL_MEETING -> cancelMeeting(a, payload);
            case SYNC_PIPELINE -> sheets.sync(a.getOrganizationId(), payload.path("pitchId").asLong(), a.getIdempotencyKey());
            case APPLY_LABELS -> {
                Email email = emails.findByIdAndOrganizationId(payload.path("emailId").asLong(), a.getOrganizationId())
                        .orElseThrow(() -> ApiException.notFound("Email"));
                List<String> add = new ArrayList<>();
                payload.path("add").forEach(x -> add.add(x.asText()));
                List<String> remove = new ArrayList<>();
                payload.path("remove").forEach(x -> remove.add(x.asText()));
                applyGmailLabels(a.getOrganizationId(), a.getWorkflowId(), email, add, remove, a.getIdempotencyKey());
            }
        }
        Approval done = approvals.findById(approvalId).orElseThrow();
        done.setStatus(Approval.Status.EXECUTED.name());
        done.setExecutedAt(clock.instant());
        done.setError(null);
        approvals.save(done);
    }

    /** The job gave up (or a permanent error): record the failure and return the workflow to the user. */
    public void approvalFailed(Long approvalId, String message) {
        Approval a = approvals.findById(approvalId).orElse(null);
        if (a == null) {
            return;
        }
        a.setStatus(Approval.Status.FAILED.name());
        a.setError(Json.truncate(message, 1000));
        approvals.save(a);
        JsonNode payload = json.read(a.getPayloadJson());
        switch (a.type()) {
            case SEND_EMAIL -> {
                drafts.findById(payload.path("draftId").asLong()).ifPresent(d -> {
                    d.setStatus("FAILED");
                    d.setFailureReason(Json.truncate(message, 1000));
                    drafts.save(d);
                });
                audit.recordAi(a.getOrganizationId(), AuditAction.EMAIL_SEND_FAILED, "APPROVAL", a.getId(), null);
                backToReview(a.getWorkflowId(), "The email was not sent: " + message);
            }
            case CREATE_MEETING -> backToReview(a.getWorkflowId(), "The meeting was not created: " + message);
            case CANCEL_MEETING -> backToReview(a.getWorkflowId(), "The meeting was not cancelled: " + message);
            default -> notifications.notify(a.getOrganizationId(), a.getDecidedByUserId(), NotificationType.INTEGRATION_ERROR,
                    "Action failed", a.getSummary() + ": " + message, a.getWorkflowId(), "approval:" + a.getId() + ":failed");
        }
        audit.recordAi(a.getOrganizationId(), AuditAction.INTEGRATION_ERROR, "APPROVAL", a.getId(), Map.of("type", a.getActionType()));
    }

    // =====================================================================================
    // Email
    // =====================================================================================

    private void sendEmail(Approval a, JsonNode p) {
        Long senderId = p.path("senderUserId").asLong();
        IntegrationConnection conn = providers.demo() ? null : integrations.require(a.getOrganizationId(), senderId, Integration.GMAIL);
        EmailProvider provider = providers.email();
        String key = a.getIdempotencyKey();
        var begin = ops.begin(a.getOrganizationId(), key, "GMAIL_SEND", a.getWorkflowId());
        EmailProvider.SentEmail sent = null;
        if (begin.alreadySucceeded()) {
            sent = new EmailProvider.SentEmail(begin.operation().getExternalId(), null);
        } else {
            if (begin.resumedPending()) {
                sent = provider.findSentByIdempotencyKey(conn, key);   // did a previous attempt get through?
            }
            if (sent == null) {
                try {
                    sent = provider.send(conn, new EmailProvider.OutgoingEmail(p.path("to").asText(), p.path("subject").asText(),
                            p.path("body").asText(), Json.text(p, "threadId"), Json.text(p, "inReplyTo")), key);
                } catch (IntegrationException e) {
                    if (!e.isRetryable()) {
                        ops.fail(key, e.getMessage());
                    }
                    throw e;
                }
            }
            ops.succeed(key, sent.messageId());
        }
        boolean demo = provider.isDemo();
        EmailDraft draft = drafts.findById(p.path("draftId").asLong()).orElseThrow(() -> ApiException.notFound("Draft"));
        draft.setStatus(demo ? "DEMO_SENT" : "SENT");
        draft.setSentAt(clock.instant());
        draft.setSentExternalId(sent.messageId());
        draft.setSentThreadId(sent.threadId());
        drafts.save(draft);
        Workflow wf = store.update(a.getWorkflowId(), false, w -> {
            if (WorkflowStatus.PROCESSING.equals(w.getStatus())) {
                WorkflowStore.waitForUser(w, "EMAIL_SENT", WorkflowEngine.REVIEW_ACTIONS, WorkflowStatus.WAITING_FOR_APPROVAL);
                w.setRecommendedAction("COMPLETE_WORKFLOW");
            }
        });
        audit.record(a.getOrganizationId(), a.getDecidedByUserId(), demo ? AuditAction.DEMO_ACTION_SIMULATED : AuditAction.EMAIL_SENT,
                "EMAIL_DRAFT", draft.getId(), Map.of("workflowId", a.getWorkflowId(), "approvalId", a.getId()));
        activity.log(a.getOrganizationId(), a.getDecidedByUserId(), "WORKFLOW", (demo ? "[Demo, not sent] " : "Email sent to ")
                + draft.getRecipient() + " — \"" + Json.truncate(draft.getSubject(), 120) + "\"");
        notifications.notify(a.getOrganizationId(), a.getDecidedByUserId(), NotificationType.EMAIL_SENT,
                demo ? "Email recorded (demo)" : "Email sent", "To " + draft.getRecipient(), wf.getId(), "draft:" + draft.getId() + ":sent");
    }

    // =====================================================================================
    // Calendar
    // =====================================================================================

    private void createMeeting(Approval a, JsonNode p) {
        Long ownerId = p.path("calendarOwnerUserId").asLong();
        IntegrationConnection conn = providers.demo() ? null : integrations.require(a.getOrganizationId(), ownerId, Integration.CALENDAR);
        CalendarProvider provider = providers.calendar();
        String key = a.getIdempotencyKey();
        Instant start = Instant.parse(p.path("start").asText());
        Instant end = Instant.parse(p.path("end").asText());
        List<String> attendees = new ArrayList<>();
        p.path("attendees").forEach(x -> attendees.add(x.asText()));
        CalendarProvider.EventRequest req = new CalendarProvider.EventRequest(p.path("title").asText(),
                p.path("description").asText(null), start, end, p.path("timezone").asText("UTC"), attendees,
                p.path("addVideoConference").asBoolean(true), List.of(30, 10));
        ops.begin(a.getOrganizationId(), key, "CALENDAR_CREATE", a.getWorkflowId());
        CalendarProvider.EventState st = provider.create(conn, conn == null ? null : conn.getCalendarId(), req, key);
        ops.succeed(key, st.eventId());

        boolean demo = provider.isDemo();
        CalendarEvent event = events.findByWorkflowIdOrderByIdDesc(a.getWorkflowId()).stream()
                .filter(e -> st.eventId() != null && st.eventId().equals(e.getExternalEventId())).findFirst()
                .orElseGet(CalendarEvent::new);
        event.setOrganizationId(a.getOrganizationId());
        event.setUserId(ownerId);
        event.setCreatedByUserId(a.getDecidedByUserId());
        event.setTitle(Json.truncate(req.title(), 250));
        event.setDescription(req.description());
        event.setStartTime(start);
        event.setEndTime(end);
        event.setPitchId(a.getPitchId());
        event.setWorkflowId(a.getWorkflowId());
        event.setSource("PITSCH");
        event.setProvider(demo ? "DEMO" : "GOOGLE");
        event.setExternalCalendarId(conn == null || conn.getCalendarId() == null ? "primary" : conn.getCalendarId());
        event.setExternalEventId(st.eventId());
        event.setSyncStatus(demo ? CalendarEvent.SyncStatus.DEMO.name() : CalendarEvent.SyncStatus.SYNCED.name());
        event.setConferenceLink(st.conferenceLink());
        event.setHtmlLink(st.htmlLink());
        event.setLocation(st.conferenceLink() != null ? "Google Meet" : null);
        event.setAttendeesJson(json.write(attendees));
        event.setLastSyncedAt(clock.instant());
        CalendarEvent saved = events.save(event);

        Workflow wf = store.update(a.getWorkflowId(), false, w -> {
            w.setMeetingStart(start);
            w.setMeetingEnd(end);
            w.setMeetingEventId(saved.getId());
            if (WorkflowStatus.PROCESSING.equals(w.getStatus())) {
                WorkflowStore.waitForUser(w, "MEETING_SCHEDULED", WorkflowEngine.REVIEW_ACTIONS, WorkflowStatus.WAITING_FOR_APPROVAL);
                w.setRecommendedAction("PLAN_EMAIL_RESPONSE");
            }
        });
        if (a.getPitchId() != null) {
            pitches.setStatus(a.getPitchId(), "MEETING_SCHEDULED");
        }
        jobs.enqueue(JobType.WORKFLOW_EVENT_ACTIONS, a.getOrganizationId(), wf.getId(),
                Map.of("workflowId", wf.getId(), "event", "MEETING_APPROVED"),
                "wf-event:" + wf.getId() + ":MEETING_APPROVED:" + saved.getId(), java.time.Duration.ZERO);
        audit.record(a.getOrganizationId(), a.getDecidedByUserId(), demo ? AuditAction.DEMO_ACTION_SIMULATED : AuditAction.MEETING_CREATED,
                "CALENDAR_EVENT", saved.getId(), Map.of("workflowId", wf.getId(), "approvalId", a.getId()));
        activity.log(a.getOrganizationId(), a.getDecidedByUserId(), "EVENT",
                (demo ? "[Demo, no invite sent] " : "Meeting scheduled: ") + saved.getTitle());
        notifications.notify(a.getOrganizationId(), a.getDecidedByUserId(), NotificationType.MEETING_SCHEDULED,
                demo ? "Meeting recorded (demo)" : "Meeting scheduled", saved.getTitle(), wf.getId(), "event:" + saved.getId() + ":created");
    }

    private void cancelMeeting(Approval a, JsonNode p) {
        CalendarEvent event = events.findByIdAndOrganizationId(p.path("eventId").asLong(), a.getOrganizationId())
                .orElseThrow(() -> ApiException.notFound("Meeting"));
        if (event.getExternalEventId() != null && !"DEMO".equals(event.getProvider())) {
            IntegrationConnection conn = integrations.require(a.getOrganizationId(), event.getUserId(), Integration.CALENDAR);
            providers.calendar().cancel(conn, event.getExternalCalendarId(), event.getExternalEventId());
        } else if (event.getExternalEventId() != null) {
            providers.calendar().cancel(null, event.getExternalCalendarId(), event.getExternalEventId());
        }
        event.setSyncStatus(CalendarEvent.SyncStatus.CANCELLED.name());
        event.setCancelledAt(clock.instant());
        events.save(event);
        store.update(a.getWorkflowId(), false, w -> {
            w.setMeetingStart(null);
            w.setMeetingEnd(null);
            w.setMeetingEventId(null);
            if (WorkflowStatus.PROCESSING.equals(w.getStatus())) {
                WorkflowStore.waitForUser(w, "MEETING_CANCELLED", WorkflowEngine.REVIEW_ACTIONS, WorkflowStatus.WAITING_FOR_APPROVAL);
            }
        });
        audit.record(a.getOrganizationId(), a.getDecidedByUserId(), AuditAction.MEETING_CANCELLED, "CALENDAR_EVENT", event.getId(), null);
        activity.log(a.getOrganizationId(), a.getDecidedByUserId(), "EVENT", "Meeting cancelled: " + event.getTitle());
    }

    // =====================================================================================
    // Gmail labels (AUTO policy executes directly; APPROVAL policy goes through executeApproval)
    // =====================================================================================

    public void applyGmailLabels(Long orgId, Long workflowId, Email email, List<String> add, List<String> remove, String key) {
        if (email.getConnectionId() == null || email.getGmailId() == null) {
            return;
        }
        IntegrationConnection conn = connections.findById(email.getConnectionId())
                .filter(c -> c.isUsableFor(Integration.GMAIL)).orElse(null);
        if (conn == null && !providers.demo()) {
            log.info("Skipping Gmail labels for email {}: mailbox no longer connected", email.getId());
            return;
        }
        var begin = ops.begin(orgId, key, "GMAIL_LABELS", workflowId);
        if (begin.alreadySucceeded()) {
            return;
        }
        providers.email().modifyLabels(conn, email.getGmailId(), add, remove);   // idempotent by nature
        ops.succeed(key, email.getGmailId());
        audit.recordAi(orgId, providers.demo() ? AuditAction.DEMO_ACTION_SIMULATED : AuditAction.LABELS_APPLIED, "EMAIL",
                email.getId(), Map.of("added", add, "removed", remove));
    }

    private void backToReview(Long workflowId, String message) {
        if (workflowId == null) {
            return;
        }
        Workflow wf = store.update(workflowId, false, w -> {
            if (WorkflowStatus.PROCESSING.equals(w.getStatus())) {
                WorkflowStore.waitForUser(w, "REVIEW", WorkflowEngine.REVIEW_ACTIONS, WorkflowStatus.WAITING_FOR_APPROVAL);
                w.setError(Json.truncate(message, 2000));
            }
        });
        notifications.notify(wf.getOrganizationId(), wf.getUserId(), NotificationType.WORKFLOW_FAILED, "Action failed", message,
                workflowId, "wf:" + workflowId + ":actionfail:" + wf.getVersion());
    }
}
