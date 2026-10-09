package com.pitsch.backend.email;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.pitsch.backend.activity.ActivityService;
import com.pitsch.backend.audit.AuditAction;
import com.pitsch.backend.audit.AuditService;
import com.pitsch.backend.billing.EntitlementService;
import com.pitsch.backend.billing.UsageMetric;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.ErrorCode;
import com.pitsch.backend.common.Hashing;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.config.PitschProperties;
import com.pitsch.backend.files.FileService;
import com.pitsch.backend.files.StoredFile;
import com.pitsch.backend.workflow.Workflow;
import com.pitsch.backend.workflow.WorkflowEngine;
import com.pitsch.backend.workflow.WorkflowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single entry point for incoming email (manual import and Gmail). It validates, de-duplicates by external ID,
 * stores attachments in object storage, persists the email and starts its workflow — all in one transaction, so a
 * rejected attachment never leaves a half-imported email behind.
 */
@Service
public class EmailIngestionService {

    private static final Logger log = LoggerFactory.getLogger(EmailIngestionService.class);
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MAX_BODY_CHARS = 200_000;

    public record IncomingFile(String filename, byte[] bytes) { }

    public record IngestCommand(Email.Source source, String sender, String senderName, String subject, String body,
                                String threadId, String messageId, String inReplyTo, String gmailId, Long connectionId,
                                Instant receivedAt, List<String> to, List<IncomingFile> files, boolean skipBadFiles) { }

    public record IngestResult(Email email, Workflow workflow, boolean duplicate, List<String> skippedFiles) { }

    private final EmailRepository emails;
    private final EmailAttachmentRepository attachments;
    private final WorkflowRepository workflows;
    private final WorkflowEngine engine;
    private final FileService files;
    private final EntitlementService entitlements;
    private final AuditService audit;
    private final ActivityService activity;
    private final PitschProperties props;

    public EmailIngestionService(EmailRepository emails, EmailAttachmentRepository attachments,
                                 WorkflowRepository workflows, WorkflowEngine engine, FileService files,
                                 EntitlementService entitlements, AuditService audit, ActivityService activity,
                                 PitschProperties props) {
        this.emails = emails;
        this.attachments = attachments;
        this.workflows = workflows;
        this.engine = engine;
        this.files = files;
        this.entitlements = entitlements;
        this.audit = audit;
        this.activity = activity;
        this.props = props;
    }

    @Transactional
    public IngestResult ingest(Long orgId, Long ownerUserId, IngestCommand cmd) {
        String sender = cmd.sender() == null ? "" : cmd.sender().trim().toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(sender).matches() || sender.length() > 320) {
            throw ApiException.badRequest("sender must be a valid email address");
        }
        List<IncomingFile> incoming = cmd.files() == null ? List.of() : cmd.files();
        if (blank(cmd.subject()) && blank(cmd.body()) && incoming.isEmpty()) {
            throw ApiException.badRequest("The email needs a subject, a body or an attachment");
        }
        if (incoming.size() > props.getUploads().getMaxFiles()) {
            throw ApiException.badRequest("At most " + props.getUploads().getMaxFiles() + " attachments");
        }

        String dedupeKey = dedupeKey(cmd);
        if (dedupeKey != null) {
            Email existing = emails.findByOrganizationIdAndDedupeKey(orgId, dedupeKey).orElse(null);
            if (existing != null) {
                audit.record(orgId, ownerUserId, AuditAction.EMAIL_DUPLICATE_IGNORED, "EMAIL", existing.getId(), null);
                return new IngestResult(existing, workflows.findFirstByEmailId(existing.getId()).orElse(null), true, List.of());
            }
        }
        // Manual imports are refused when the quota is reached; Gmail emails are kept and their workflow parked.
        boolean quotaOk = entitlements.allows(orgId, UsageMetric.AI_WORKFLOWS, 1);
        if (!quotaOk && cmd.source() == Email.Source.MANUAL) {
            entitlements.require(orgId, UsageMetric.AI_WORKFLOWS, 1);
        }

        Email email = new Email();
        email.setOrganizationId(orgId);
        email.setUserId(ownerUserId);
        email.setSource(cmd.source().name());
        email.setDedupeKey(dedupeKey);
        email.setConnectionId(cmd.connectionId());
        email.setGmailId(trim(cmd.gmailId(), 255));
        email.setSender(sender);
        email.setSenderName(trim(cmd.senderName(), 255));
        email.setSubject(trim(cmd.subject(), 1000));
        email.setBody(cmd.body() == null ? "" : Json.truncate(cmd.body(), MAX_BODY_CHARS));
        email.setThreadId(trim(cmd.threadId(), 255));
        email.setMessageId(trim(cmd.messageId(), 255));
        email.setInReplyTo(trim(cmd.inReplyTo(), 255));
        email.setRecipients(cmd.to() == null ? null : Json.truncate(String.join(",", cmd.to()), 2000));
        email.setReceivedAt(cmd.receivedAt());
        email = emails.save(email);

        List<String> skipped = new ArrayList<>();
        for (IncomingFile f : incoming) {
            StoredFile stored;
            try {
                stored = files.store(orgId, ownerUserId, f.filename(), f.bytes());
            } catch (ApiException e) {
                if (!cmd.skipBadFiles()) {
                    throw new ApiException(e.getCode(), (f.filename() == null ? "Attachment" : f.filename()) + ": " + e.getMessage());
                }
                skipped.add(f.filename());
                continue;
            }
            EmailAttachment a = new EmailAttachment();
            a.setOrganizationId(orgId);
            a.setEmailId(email.getId());
            a.setFilename(stored.getFilename());
            a.setMimeType(stored.getContentType());
            a.setSizeBytes(stored.getSizeBytes());
            a.setStoredFileId(stored.getId());
            a.setContentSha256(stored.getSha256());
            attachments.save(a);
        }

        Workflow wf = engine.createForEmail(email, quotaOk);
        audit.record(orgId, ownerUserId, AuditAction.EMAIL_IMPORTED, "EMAIL", email.getId(),
                Map.of("source", cmd.source().name(), "attachments", incoming.size() - skipped.size(),
                        "skippedAttachments", skipped.size(), "workflowId", wf.getId()));
        activity.log(orgId, ownerUserId, "WORKFLOW", "Email received from " + sender + ": " + wf.getSummary());
        if (!skipped.isEmpty()) {
            log.info("Skipped {} unsupported attachment(s) on email {}", skipped.size(), email.getId());
        }
        return new IngestResult(email, wf, false, skipped);
    }

    static String dedupeKey(IngestCommand cmd) {
        if (cmd.gmailId() != null && !cmd.gmailId().isBlank()) {
            return "gmail:" + cmd.gmailId().trim();
        }
        if (cmd.messageId() != null && !cmd.messageId().isBlank()) {
            return "msgid:" + Hashing.sha256Hex(cmd.messageId().trim().toLowerCase(Locale.ROOT));
        }
        return null;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trim(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** Thrown for clearly malformed import requests. */
    static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }
}
