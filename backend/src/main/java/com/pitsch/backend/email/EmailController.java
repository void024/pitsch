package com.pitsch.backend.email;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pitsch.backend.auth.AuthPrincipal;
import com.pitsch.backend.auth.Permission;
import com.pitsch.backend.auth.RequiresPermission;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.ErrorCode;
import com.pitsch.backend.common.PageResponse;
import com.pitsch.backend.common.Pagination;
import com.pitsch.backend.common.RateLimiter;
import com.pitsch.backend.config.PitschProperties;
import com.pitsch.backend.files.StoredFile;
import com.pitsch.backend.files.StoredFileRepository;
import com.pitsch.backend.files.FileService;
import com.pitsch.backend.workflow.Workflow;
import com.pitsch.backend.workflow.WorkflowRepository;
import com.pitsch.backend.workflow.WorkflowViews;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Manual email import (the UI's "Submit email" dialog or any trusted integration) plus read access to imported email.
 * Both entry points go through {@link EmailIngestionService}, which de-duplicates, validates files and starts the
 * workflow. Gmail ingestion uses the same service from the sync jobs.
 */
@RestController
public class EmailController {

    /** An attachment as base64; a "data:...;base64," prefix (from the browser's FileReader) is accepted. */
    public record AttachmentInput(String filename, String mimeType, String contentBase64) { }

    public record ProcessEmailRequest(String sender, String senderName, String subject, String body, String threadId,
                                      String messageId, String inReplyTo, String receivedAt,
                                      List<String> to, List<AttachmentInput> attachments) { }

    public record ImportResponse(Long emailId, Long workflowId, boolean duplicate, List<String> skippedFiles,
                                 WorkflowViews.WorkflowView workflow) { }

    public record DownloadView(String url, String filename, String mimeType, long sizeBytes, Instant expiresAt) { }

    private static final Map<String, String> SORTABLE = Map.of("receivedAt", "receivedAt", "createdAt", "createdAt",
            "sender", "sender");

    private final EmailIngestionService ingestion;
    private final EmailRepository emails;
    private final EmailAttachmentRepository attachments;
    private final StoredFileRepository storedFiles;
    private final FileService files;
    private final WorkflowRepository workflows;
    private final WorkflowViews views;
    private final RateLimiter limiter;
    private final PitschProperties props;

    private final EmailDeletionService deletion;

    public EmailController(EmailIngestionService ingestion, EmailRepository emails, EmailAttachmentRepository attachments,
                           StoredFileRepository storedFiles, FileService files, WorkflowRepository workflows,
                           WorkflowViews views, RateLimiter limiter, PitschProperties props,
                           EmailDeletionService deletion) {
        this.deletion = deletion;
        this.ingestion = ingestion;
        this.emails = emails;
        this.attachments = attachments;
        this.storedFiles = storedFiles;
        this.files = files;
        this.workflows = workflows;
        this.views = views;
        this.limiter = limiter;
        this.props = props;
    }

    /** JSON import with base64 attachments (original frontend contract). */
    @PostMapping({"/api/emails/process", "/api/v1/emails"})
    @RequiresPermission(Permission.EMAIL_IMPORT)
    public ResponseEntity<ImportResponse> process(AuthPrincipal principal, @RequestBody ProcessEmailRequest req) {
        limiter.check("ai:" + principal.userId(), props.getRateLimit().getAiPerMinute());
        List<EmailIngestionService.IncomingFile> incoming = new ArrayList<>();
        if (req.attachments() != null) {
            if (req.attachments().size() > props.getUploads().getMaxFiles()) {
                throw ApiException.badRequest("At most " + props.getUploads().getMaxFiles() + " attachments");
            }
            for (AttachmentInput a : req.attachments()) {
                incoming.add(new EmailIngestionService.IncomingFile(a.filename(), decode(a)));
            }
        }
        return accepted(principal, new EmailIngestionService.IngestCommand(Email.Source.MANUAL, req.sender(),
                req.senderName(), req.subject(), req.body(), req.threadId(), req.messageId(), req.inReplyTo(), null, null,
                parseReceivedAt(req.receivedAt()), req.to(), incoming, false));
    }

    /** Multipart import: fields plus up to N files (no base64 overhead; preferred by the new frontend). */
    @PostMapping(value = "/api/v1/emails/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission(Permission.EMAIL_IMPORT)
    public ResponseEntity<ImportResponse> importMultipart(AuthPrincipal principal,
                                                          @RequestParam String sender,
                                                          @RequestParam(required = false) String senderName,
                                                          @RequestParam(required = false) String subject,
                                                          @RequestParam(required = false) String body,
                                                          @RequestParam(required = false) String threadId,
                                                          @RequestParam(required = false) String messageId,
                                                          @RequestParam(required = false) String inReplyTo,
                                                          @RequestParam(required = false) String receivedAt,
                                                          @RequestPart(name = "files", required = false) List<MultipartFile> uploads) {
        limiter.check("ai:" + principal.userId(), props.getRateLimit().getAiPerMinute());
        List<EmailIngestionService.IncomingFile> incoming = new ArrayList<>();
        if (uploads != null) {
            if (uploads.size() > props.getUploads().getMaxFiles()) {
                throw ApiException.badRequest("At most " + props.getUploads().getMaxFiles() + " attachments");
            }
            for (MultipartFile f : uploads) {
                if (f.isEmpty()) {
                    continue;
                }
                if (f.getSize() > props.getUploads().maxFileBytes()) {
                    throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE,
                            f.getOriginalFilename() + " is larger than " + props.getUploads().getMaxFileMb() + " MB");
                }
                try {
                    incoming.add(new EmailIngestionService.IncomingFile(f.getOriginalFilename(), f.getBytes()));
                } catch (IOException e) {
                    throw ApiException.badRequest("Could not read " + f.getOriginalFilename());
                }
            }
        }
        return accepted(principal, new EmailIngestionService.IngestCommand(Email.Source.MANUAL, sender, senderName,
                subject, body, threadId, messageId, inReplyTo, null, null, parseReceivedAt(receivedAt), null, incoming, false));
    }

    private ResponseEntity<ImportResponse> accepted(AuthPrincipal principal, EmailIngestionService.IngestCommand cmd) {
        EmailIngestionService.IngestResult r = ingestion.ingest(principal.orgId(), principal.userId(), cmd);
        Workflow wf = r.workflow() == null ? null : workflows.findById(r.workflow().getId()).orElse(r.workflow());
        ImportResponse body = new ImportResponse(r.email().getId(), wf == null ? null : wf.getId(), r.duplicate(),
                r.skippedFiles(), wf == null ? null : views.summary(wf));
        return ResponseEntity.status(r.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(body);
    }

    /** Legacy: newest 200 emails as a plain array. */
    @GetMapping("/api/emails")
    @RequiresPermission(Permission.PITCH_READ)
    public List<WorkflowViews.EmailView> legacyList(AuthPrincipal principal) {
        Pageable p = Pagination.of(0, 100, null, SORTABLE, "receivedAt,desc");
        return views.emailViews(emails.findByOrganizationId(principal.orgId(), p).getContent());
    }

    @GetMapping("/api/v1/emails")
    @RequiresPermission(Permission.PITCH_READ)
    public PageResponse<WorkflowViews.EmailView> list(AuthPrincipal principal,
                                                      @RequestParam(required = false) Integer page,
                                                      @RequestParam(required = false) Integer size,
                                                      @RequestParam(required = false) String sort) {
        Page<Email> result = emails.findByOrganizationId(principal.orgId(),
                Pagination.of(page, size, sort, SORTABLE, "receivedAt,desc"));
        return new PageResponse<>(views.emailViews(result.getContent()), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    /** Erase an email and everything derived from it (data-subject requests, mistaken imports). */
    @DeleteMapping("/api/v1/emails/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.PITCH_DELETE)
    public void delete(AuthPrincipal principal, @PathVariable Long id) {
        deletion.delete(principal.orgId(), principal.userId(), id);
    }

    @GetMapping({"/api/emails/{id}", "/api/v1/emails/{id}"})
    @RequiresPermission(Permission.PITCH_READ)
    public Map<String, Object> get(AuthPrincipal principal, @PathVariable Long id) {
        Email e = emails.findByIdAndOrganizationId(id, principal.orgId()).orElseThrow(() -> ApiException.notFound("Email"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("email", views.emailView(e));
        out.put("workflowId", workflows.findFirstByEmailId(id).map(Workflow::getId).orElse(null));
        return out;
    }

    /**
     * A short-lived signed download URL for an attachment. Files are never streamed from the API with their declared
     * content type, so a malicious upload cannot execute in the app's origin.
     */
    @GetMapping("/api/v1/emails/{emailId}/attachments/{attachmentId}/download")
    @RequiresPermission(Permission.PITCH_READ)
    public ResponseEntity<?> download(AuthPrincipal principal, @PathVariable Long emailId, @PathVariable Long attachmentId) {
        Long org = principal.orgId();
        emails.findByIdAndOrganizationId(emailId, org).orElseThrow(() -> ApiException.notFound("Email"));
        EmailAttachment a = attachments.findById(attachmentId)
                .filter(x -> org.equals(x.getOrganizationId()) && emailId.equals(x.getEmailId()))
                .orElseThrow(() -> ApiException.notFound("Attachment"));
        if (a.getStoredFileId() != null) {
            StoredFile f = storedFiles.findByIdAndOrganizationId(a.getStoredFileId(), org)
                    .orElseThrow(() -> ApiException.notFound("Attachment"));
            URI url = files.downloadUrl(f);
            Instant expires = Instant.now().plusSeconds(props.getStorage().getSignedUrlMinutes() * 60L);
            return ResponseEntity.ok(new DownloadView(url.toString(), f.getFilename(), f.getContentType(),
                    f.getSizeBytes(), expires));
        }
        if (a.getContentBase64() != null) {
            // Pre-upgrade attachment kept in the database: served as an opaque download, never rendered inline.
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(a.getContentBase64());
            } catch (IllegalArgumentException ex) {
                throw ApiException.notFound("Attachment");
            }
            String safeName = (a.getFilename() == null ? "attachment" : a.getFilename()).replaceAll("[^A-Za-z0-9._ -]", "_");
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header("Content-Disposition", "attachment; filename=\"" + safeName + "\"")
                    .header("X-Content-Type-Options", "nosniff")
                    .body(bytes);
        }
        throw ApiException.notFound("Attachment");
    }

    private byte[] decode(AttachmentInput a) {
        String content = a.contentBase64();
        if (content == null || content.isBlank()) {
            throw ApiException.badRequest((a.filename() == null ? "Attachment" : a.filename()) + " has no content");
        }
        int comma = content.indexOf(',');
        String clean = (content.startsWith("data:") && comma > 0 ? content.substring(comma + 1) : content).replaceAll("\\s", "");
        // Reject before decoding so an oversized payload never allocates its full byte array.
        long approx = clean.length() * 3L / 4;
        if (approx > props.getUploads().maxFileBytes() + 3) {
            throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE,
                    a.filename() + " is larger than " + props.getUploads().getMaxFileMb() + " MB");
        }
        try {
            return Base64.getDecoder().decode(clean);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest((a.filename() == null ? "Attachment" : a.filename()) + " is not valid base64");
        }
    }

    static Instant parseReceivedAt(String value) {
        if (value == null || value.isBlank()) {
            return Instant.now();
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("receivedAt must be an ISO date-time with offset");
        }
    }
}
