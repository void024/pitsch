package com.pitsch.backend.privacy;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pitsch.backend.audit.AuditEvent;
import com.pitsch.backend.audit.AuditEventRepository;
import com.pitsch.backend.auth.Session;
import com.pitsch.backend.auth.SessionRepository;
import com.pitsch.backend.auth.User;
import com.pitsch.backend.auth.UserRepository;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.email.Email;
import com.pitsch.backend.email.EmailRepository;
import com.pitsch.backend.event.CalendarEventRepository;
import com.pitsch.backend.event.EventController;
import com.pitsch.backend.files.FileService;
import com.pitsch.backend.files.StoredFile;
import com.pitsch.backend.files.StoredFileRepository;
import com.pitsch.backend.integration.IntegrationConnection;
import com.pitsch.backend.integration.IntegrationConnectionRepository;
import com.pitsch.backend.notification.Notification;
import com.pitsch.backend.notification.NotificationRepository;
import com.pitsch.backend.org.Membership;
import com.pitsch.backend.org.MembershipRepository;
import com.pitsch.backend.org.Organization;
import com.pitsch.backend.org.OrganizationRepository;
import com.pitsch.backend.pitch.Pitch;
import com.pitsch.backend.pitch.PitchController;
import com.pitsch.backend.pitch.PitchRepository;
import com.pitsch.backend.task.TaskRepository;
import com.pitsch.backend.user.UserSettingsRepository;
import com.pitsch.backend.workflow.EmailDraft;
import com.pitsch.backend.workflow.EmailDraftRepository;
import com.pitsch.backend.workflow.Workflow;
import com.pitsch.backend.workflow.WorkflowRepository;
import com.pitsch.backend.workflow.WorkflowViews;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Data portability. A workspace export is a ZIP with {@code workspace.json} (every record the workspace owns, in API
 * shapes) and {@code files/} (original attachments). A personal export contains the user's own account data.
 * OAuth tokens, password hashes and session secrets are never exported.
 */
@Service
public class DataExportService {

    private static final Logger log = LoggerFactory.getLogger(DataExportService.class);
    private static final int PAGE = 200;

    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;
    private final UserRepository users;
    private final PitchRepository pitches;
    private final EmailRepository emails;
    private final WorkflowRepository workflows;
    private final WorkflowViews views;
    private final EmailDraftRepository drafts;
    private final CalendarEventRepository events;
    private final TaskRepository tasks;
    private final StoredFileRepository storedFiles;
    private final FileService files;
    private final AuditEventRepository auditEvents;
    private final NotificationRepository notifications;
    private final SessionRepository sessions;
    private final IntegrationConnectionRepository connections;
    private final UserSettingsRepository settings;
    private final Json json;

    public DataExportService(OrganizationRepository organizations, MembershipRepository memberships, UserRepository users,
                             PitchRepository pitches, EmailRepository emails, WorkflowRepository workflows,
                             WorkflowViews views, EmailDraftRepository drafts, CalendarEventRepository events,
                             TaskRepository tasks, StoredFileRepository storedFiles, FileService files,
                             AuditEventRepository auditEvents, NotificationRepository notifications,
                             SessionRepository sessions, IntegrationConnectionRepository connections,
                             UserSettingsRepository settings, Json json) {
        this.organizations = organizations;
        this.memberships = memberships;
        this.users = users;
        this.pitches = pitches;
        this.emails = emails;
        this.workflows = workflows;
        this.views = views;
        this.drafts = drafts;
        this.events = events;
        this.tasks = tasks;
        this.storedFiles = storedFiles;
        this.files = files;
        this.auditEvents = auditEvents;
        this.notifications = notifications;
        this.sessions = sessions;
        this.connections = connections;
        this.settings = settings;
        this.json = json;
    }

    /** Streams the workspace export as a ZIP into {@code out}. */
    @Transactional(readOnly = true)
    public void exportWorkspace(Long orgId, OutputStream out) throws IOException {
        Organization org = organizations.findById(orgId).orElseThrow(() -> ApiException.notFound("Workspace"));
        ObjectMapper mapper = json.mapper().copy().disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("workspace.json"));
            try (JsonGenerator g = mapper.getFactory().createGenerator(zip)) {
                g.useDefaultPrettyPrinter();
                g.writeStartObject();
                g.writeStringField("format", "pitsch-workspace-export/v1");
                g.writeStringField("exportedAt", Instant.now().toString());
                Map<String, Object> ws = new LinkedHashMap<>();
                ws.put("id", org.getId());
                ws.put("name", org.getName());
                ws.put("timezone", org.getTimezone());
                ws.put("createdAt", org.getCreatedAt());
                g.writeObjectField("workspace", ws);

                g.writeArrayFieldStart("members");
                for (Membership m : memberships.findByOrganizationIdOrderByCreatedAtAsc(orgId)) {
                    User u = users.findById(m.getUserId()).orElse(null);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("userId", m.getUserId());
                    row.put("name", u == null ? null : u.getName());
                    row.put("email", u == null ? null : u.getEmail());
                    row.put("role", m.getRole().name());
                    row.put("joinedAt", m.getCreatedAt());
                    g.writeObject(row);
                }
                g.writeEndArray();

                g.writeArrayFieldStart("pitches");
                for (int p = 0; ; p++) {
                    Page<Pitch> page = pitches.findAll((root, q, cb) -> cb.equal(root.get("organizationId"), orgId),
                            PageRequest.of(p, PAGE, Sort.by("id")));
                    for (Pitch x : page) {
                        g.writeObject(PitchController.PitchView.of(x));
                    }
                    if (!page.hasNext()) {
                        break;
                    }
                }
                g.writeEndArray();

                g.writeArrayFieldStart("emails");
                for (int p = 0; ; p++) {
                    Page<Email> page = emails.findByOrganizationId(orgId, PageRequest.of(p, PAGE, Sort.by("id")));
                    for (WorkflowViews.EmailView v : views.emailViews(page.getContent())) {
                        g.writeObject(v);
                    }
                    if (!page.hasNext()) {
                        break;
                    }
                }
                g.writeEndArray();

                g.writeArrayFieldStart("workflows");
                List<Long> workflowIds = new ArrayList<>();
                for (int p = 0; ; p++) {
                    Page<Workflow> page = workflows.findAll((root, q, cb) -> cb.equal(root.get("organizationId"), orgId),
                            PageRequest.of(p, 50, Sort.by("id")));
                    for (Workflow w : page) {
                        workflowIds.add(w.getId());
                        g.writeObject(views.detail(w));
                    }
                    if (!page.hasNext()) {
                        break;
                    }
                }
                g.writeEndArray();

                g.writeArrayFieldStart("emailDrafts");
                for (EmailDraft d : drafts.findByOrganizationId(orgId)) {
                    g.writeObject(views.draftView(d));
                }
                g.writeEndArray();

                g.writeArrayFieldStart("calendarEvents");
                for (var e : events.findByOrganizationIdOrderByStartTimeAsc(orgId)) {
                    g.writeObject(EventController.view(e));
                }
                g.writeEndArray();

                g.writeObjectField("tasks", tasks.findByOrganizationIdOrderByCreatedAtDesc(orgId));

                g.writeArrayFieldStart("files");
                List<StoredFile> stored = storedFiles.findByOrganizationId(orgId);
                for (StoredFile f : stored) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", f.getId());
                    row.put("filename", f.getFilename());
                    row.put("contentType", f.getContentType());
                    row.put("sizeBytes", f.getSizeBytes());
                    row.put("sha256", f.getSha256());
                    row.put("path", zipPath(f));
                    g.writeObject(row);
                }
                g.writeEndArray();

                g.writeArrayFieldStart("auditEvents");
                for (AuditEvent e : auditEvents.findTop5000ByOrganizationIdOrderByIdDesc(orgId)) {
                    g.writeObject(audit(e));
                }
                g.writeEndArray();
                g.writeEndObject();
            }
            zip.closeEntry();

            for (StoredFile f : storedFiles.findByOrganizationId(orgId)) {
                byte[] bytes;
                try {
                    bytes = files.read(f);
                } catch (RuntimeException e) {
                    log.warn("Export: could not read stored file {} (skipped)", f.getId());
                    continue;
                }
                zip.putNextEntry(new ZipEntry(zipPath(f)));
                zip.write(bytes);
                zip.closeEntry();
            }
            zip.finish();
        }
    }

    /** The signed-in user's own data (profile, memberships, sessions, notifications, settings, own audit trail). */
    @Transactional(readOnly = true)
    public Map<String, Object> exportUser(Long userId) {
        User u = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("format", "pitsch-account-export/v1");
        out.put("exportedAt", Instant.now());
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", u.getId());
        profile.put("name", u.getName());
        profile.put("email", u.getEmail());
        profile.put("emailVerifiedAt", u.getEmailVerifiedAt());
        profile.put("createdAt", u.getCreatedAt());
        profile.put("lastLoginAt", u.getLastLoginAt());
        profile.put("hasPassword", !com.pitsch.backend.auth.PasswordHasher.NO_PASSWORD.equals(u.getPasswordHash()));
        out.put("profile", profile);
        out.put("settings", settings.findByUserId(userId).orElse(null));
        List<Map<String, Object>> ms = new ArrayList<>();
        for (Membership m : memberships.findByUserIdOrderByCreatedAtAsc(userId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("workspaceId", m.getOrganizationId());
            row.put("workspaceName", organizations.findById(m.getOrganizationId()).map(Organization::getName).orElse(null));
            row.put("role", m.getRole().name());
            row.put("joinedAt", m.getCreatedAt());
            ms.add(row);
        }
        out.put("memberships", ms);
        List<Map<String, Object>> ss = new ArrayList<>();
        for (Session s : sessions.findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByLastSeenAtDesc(userId, Instant.now())) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("createdAt", s.getCreatedAt());
            row.put("lastSeenAt", s.getLastSeenAt());
            row.put("userAgent", s.getUserAgent());
            row.put("ipAddress", s.getIpAddress());
            ss.add(row);
        }
        out.put("activeSessions", ss);
        List<Map<String, Object>> conns = new ArrayList<>();
        for (IntegrationConnection c : connections.findByUserId(userId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("workspaceId", c.getOrganizationId());
            row.put("provider", c.getProvider());
            row.put("accountEmail", c.getAccountEmail());
            row.put("status", c.getStatus());
            row.put("enabled", c.getEnabledIntegrations());
            conns.add(row);
        }
        out.put("integrations", conns);
        List<Map<String, Object>> ns = new ArrayList<>();
        for (Notification n : notifications.findByUserIdOrderByCreatedAtDesc(userId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("workspaceId", n.getOrganizationId());
            row.put("type", n.getType());
            row.put("title", n.getTitle());
            row.put("message", n.getMessage());
            row.put("read", n.isRead());
            row.put("createdAt", n.getCreatedAt());
            ns.add(row);
        }
        out.put("notifications", ns);
        out.put("auditTrail", auditEvents.findTop2000ByActorUserIdOrderByIdDesc(userId).stream().map(this::audit).toList());
        return out;
    }

    private Map<String, Object> audit(AuditEvent e) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", e.getId());
        row.put("workspaceId", e.getOrganizationId());
        row.put("action", e.getAction());
        row.put("actorType", e.getActorType());
        row.put("actorUserId", e.getActorUserId());
        row.put("resourceType", e.getResourceType());
        row.put("resourceId", e.getResourceId());
        row.put("ipAddress", e.getIpAddress());
        row.put("metadata", json.read(e.getMetadataJson()));
        row.put("createdAt", e.getCreatedAt());
        return row;
    }

    private static String zipPath(StoredFile f) {
        String name = f.getFilename() == null ? "file" : f.getFilename().replaceAll("[^A-Za-z0-9._-]", "_");
        return "files/" + f.getId() + "-" + name;
    }
}
