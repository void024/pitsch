package com.pitsch.backend.email;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.pitsch.backend.auth.AuthInterceptor;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.workflow.Workflow;
import com.pitsch.backend.workflow.WorkflowEngine;
import com.pitsch.backend.workflow.WorkflowRepository;
import com.pitsch.backend.workflow.WorkflowViews;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/emails")
public class EmailController {

    /** An attachment as base64; a "data:...;base64," prefix (from the browser's FileReader) is accepted. */
    public record AttachmentInput(String filename, String mimeType, String contentBase64) { }

    /**
     * An incoming email. Today it is submitted from the Pitsch UI or any webhook; a Gmail poller can post the
     * same shape (with gmailId/threadId/messageId) later.
     */
    public record ProcessEmailRequest(String sender, String senderName, String subject, String body, String threadId,
                                      String messageId, String inReplyTo, String gmailId, String receivedAt,
                                      List<String> to, List<AttachmentInput> attachments) { }

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MAX_ATTACHMENTS = 10;
    // 10 MB: base64 of a larger file exceeds Jackson's default 20M-character string limit.
    private static final long MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024;

    private final EmailRepository emails;
    private final EmailAttachmentRepository attachments;
    private final WorkflowRepository workflows;
    private final WorkflowEngine engine;
    private final WorkflowViews views;

    public EmailController(EmailRepository emails, EmailAttachmentRepository attachments, WorkflowRepository workflows,
                           WorkflowEngine engine, WorkflowViews views) {
        this.emails = emails;
        this.attachments = attachments;
        this.workflows = workflows;
        this.engine = engine;
        this.views = views;
    }

    @PostMapping("/process")
    public ResponseEntity<Map<String, Object>> process(@RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                                       @RequestBody ProcessEmailRequest req) {
        String sender = req.sender() == null ? "" : req.sender().trim().toLowerCase();
        if (!EMAIL.matcher(sender).matches()) {
            throw ApiException.badRequest("sender must be a valid email address");
        }
        if ((req.subject() == null || req.subject().isBlank()) && (req.body() == null || req.body().isBlank())
                && (req.attachments() == null || req.attachments().isEmpty())) {
            throw ApiException.badRequest("The email needs a subject, a body or an attachment");
        }
        List<AttachmentInput> files = req.attachments() == null ? List.of() : req.attachments();
        if (files.size() > MAX_ATTACHMENTS) {
            throw ApiException.badRequest("At most " + MAX_ATTACHMENTS + " attachments");
        }

        Email email = new Email();
        email.setUserId(userId);
        email.setSender(sender);
        email.setSenderName(blankToNull(req.senderName()));
        email.setSubject(Json.truncate(blankToNull(req.subject()), 1000));
        email.setBody(req.body() == null ? "" : req.body());
        email.setThreadId(blankToNull(req.threadId()));
        email.setMessageId(blankToNull(req.messageId()));
        email.setInReplyTo(blankToNull(req.inReplyTo()));
        email.setGmailId(blankToNull(req.gmailId()));
        email.setRecipients(req.to() == null ? null : Json.truncate(String.join(",", req.to()), 2000));
        email.setReceivedAt(parseReceivedAt(req.receivedAt()));
        email = emails.save(email);

        for (AttachmentInput f : files) {
            String content = stripDataUrl(f.contentBase64());
            long size = estimateBytes(content);
            if (size > MAX_ATTACHMENT_BYTES) {
                throw ApiException.badRequest(f.filename() + " is larger than 10 MB");
            }
            EmailAttachment a = new EmailAttachment();
            a.setEmailId(email.getId());
            a.setFilename(f.filename() == null || f.filename().isBlank() ? "attachment" : f.filename());
            a.setMimeType(f.mimeType() == null || f.mimeType().isBlank() ? "application/octet-stream" : f.mimeType());
            a.setSizeBytes(size);
            a.setContentBase64(content);
            attachments.save(a);
        }

        Workflow wf = engine.startForEmail(email);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("emailId", email.getId());
        out.put("workflowId", wf.getId());
        out.put("workflow", views.summary(workflows.findById(wf.getId()).orElse(wf)));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(out);
    }

    @GetMapping
    public List<WorkflowViews.EmailView> list(@RequestAttribute(AuthInterceptor.USER_ID) Long userId) {
        return emails.findByUserIdOrderByReceivedAtDesc(userId).stream().map(views::emailView).toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@RequestAttribute(AuthInterceptor.USER_ID) Long userId, @PathVariable Long id) {
        Email e = emails.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Email"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("email", views.emailView(e));
        out.put("workflowId", workflows.findFirstByEmailId(id).map(Workflow::getId).orElse(null));
        return out;
    }

    private static String stripDataUrl(String content) {
        if (content == null) {
            return null;
        }
        int comma = content.indexOf(',');
        String clean = content.startsWith("data:") && comma > 0 ? content.substring(comma + 1) : content;
        return clean.replaceAll("\\s", "");
    }

    private static long estimateBytes(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return 0;
        }
        long padding = base64.endsWith("==") ? 2 : base64.endsWith("=") ? 1 : 0;
        return base64.length() * 3L / 4 - padding;
    }

    private static Instant parseReceivedAt(String value) {
        if (value == null || value.isBlank()) {
            return Instant.now();
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("receivedAt must be an ISO date-time with offset");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
