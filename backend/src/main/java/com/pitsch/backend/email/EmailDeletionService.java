package com.pitsch.backend.email;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pitsch.backend.approval.Approval;
import com.pitsch.backend.approval.ApprovalRepository;
import com.pitsch.backend.audit.AuditAction;
import com.pitsch.backend.audit.AuditService;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.ErrorCode;
import com.pitsch.backend.event.CalendarEventRepository;
import com.pitsch.backend.files.FileService;
import com.pitsch.backend.files.StoredFileRepository;
import com.pitsch.backend.jobs.JobRepository;
import com.pitsch.backend.notification.NotificationRepository;
import com.pitsch.backend.pitch.PitchRepository;
import com.pitsch.backend.workflow.AgentExecutionRepository;
import com.pitsch.backend.workflow.EmailDraftRepository;
import com.pitsch.backend.workflow.Workflow;
import com.pitsch.backend.workflow.WorkflowRepository;
import com.pitsch.backend.workflow.WorkflowStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Erases one email and everything derived from it (attachments and their stored objects, its workflows with their
 * agent executions, drafts and approvals). Used for data-subject requests (a founder asks to be forgotten) and for
 * mistaken imports. Meetings that were really created stay (they are calendar facts) but lose the workflow link;
 * pitches stay unless deleted separately. The audit trail records the deletion without content.
 */
@Service
public class EmailDeletionService {

    private static final Set<String> OPEN_APPROVALS = Set.of(Approval.Status.APPROVED.name(), Approval.Status.EXECUTING.name());

    private final EmailRepository emails;
    private final EmailAttachmentRepository attachments;
    private final StoredFileRepository storedFiles;
    private final FileService files;
    private final WorkflowRepository workflows;
    private final AgentExecutionRepository executions;
    private final EmailDraftRepository drafts;
    private final ApprovalRepository approvals;
    private final JobRepository jobs;
    private final CalendarEventRepository events;
    private final NotificationRepository notifications;
    private final PitchRepository pitches;
    private final AuditService audit;

    public EmailDeletionService(EmailRepository emails, EmailAttachmentRepository attachments,
                                StoredFileRepository storedFiles, FileService files, WorkflowRepository workflows,
                                AgentExecutionRepository executions, EmailDraftRepository drafts,
                                ApprovalRepository approvals, JobRepository jobs, CalendarEventRepository events,
                                NotificationRepository notifications, PitchRepository pitches, AuditService audit) {
        this.emails = emails;
        this.attachments = attachments;
        this.storedFiles = storedFiles;
        this.files = files;
        this.workflows = workflows;
        this.executions = executions;
        this.drafts = drafts;
        this.approvals = approvals;
        this.jobs = jobs;
        this.events = events;
        this.notifications = notifications;
        this.pitches = pitches;
        this.audit = audit;
    }

    @Transactional
    public void delete(Long orgId, Long actorUserId, Long emailId) {
        Email email = emails.findByIdAndOrganizationId(emailId, orgId).orElseThrow(() -> ApiException.notFound("Email"));
        List<Workflow> wfs = workflows.findByOrganizationIdAndEmailId(orgId, emailId);
        List<Long> ids = wfs.stream().map(Workflow::getId).toList();
        if (wfs.stream().anyMatch(w -> WorkflowStatus.ACTIVE.contains(w.getStatus()))) {
            throw new ApiException(ErrorCode.CONFLICT, "The agents are still working on this email. Stop the workflow first.");
        }
        if (!ids.isEmpty() && (jobs.countByWorkflowIdInAndStatus(ids, "RUNNING") > 0
                || approvals.countByWorkflowIdInAndStatusIn(ids, OPEN_APPROVALS) > 0)) {
            throw new ApiException(ErrorCode.CONFLICT, "An approved action for this email is still being executed. Try again shortly.");
        }

        int fileCount = 0;
        for (EmailAttachment a : attachments.findByEmailIdOrderByIdAsc(emailId)) {
            if (a.getStoredFileId() != null) {
                storedFiles.findByIdAndOrganizationId(a.getStoredFileId(), orgId).ifPresent(files::delete);
                fileCount++;
            }
            attachments.delete(a);
        }
        if (!ids.isEmpty()) {
            jobs.deleteNotRunningForWorkflows(ids);
            executions.deleteByWorkflowIds(ids);
            drafts.deleteByWorkflowIds(ids);
            approvals.deleteByWorkflowIds(ids);
            events.detachWorkflows(ids);
            notifications.detachWorkflows(ids);
            pitches.clearLatestWorkflow(orgId, ids);
            pitches.clearLatestBriefWorkflow(orgId, ids);
            workflows.deleteAll(wfs);
        }
        emails.delete(email);
        audit.record(orgId, actorUserId, AuditAction.EMAIL_DELETED, "EMAIL", emailId,
                Map.of("workflowsDeleted", ids.size(), "filesDeleted", fileCount));
    }
}
