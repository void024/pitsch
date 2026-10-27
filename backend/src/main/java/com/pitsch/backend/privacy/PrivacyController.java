package com.pitsch.backend.privacy;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;

import com.pitsch.backend.audit.AuditAction;
import com.pitsch.backend.audit.AuditService;
import com.pitsch.backend.auth.AllowUnverified;
import com.pitsch.backend.auth.AllowWithoutWorkspace;
import com.pitsch.backend.auth.AuthPrincipal;
import com.pitsch.backend.auth.Permission;
import com.pitsch.backend.auth.RequiresPermission;
import com.pitsch.backend.auth.SessionCookies;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.RateLimiter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Data export and deletion (GDPR-style portability and erasure). See docs/PRIVACY.md. */
@RestController
public class PrivacyController {

    public record DeleteWorkspaceRequest(String confirmName, String password) { }

    public record DeleteAccountRequest(String confirm, String password) { }

    private final DataExportService exports;
    private final DeletionService deletion;
    private final AuditService audit;
    private final RateLimiter limiter;
    private final SessionCookies cookies;

    public PrivacyController(DataExportService exports, DeletionService deletion, AuditService audit,
                             RateLimiter limiter, SessionCookies cookies) {
        this.exports = exports;
        this.deletion = deletion;
        this.audit = audit;
        this.limiter = limiter;
        this.cookies = cookies;
    }

    /** ZIP with workspace.json and the original files. */
    @GetMapping("/api/v1/workspace/export")
    @RequiresPermission(Permission.DATA_EXPORT)
    public void exportWorkspace(AuthPrincipal principal, HttpServletResponse response) throws IOException {
        limiter.check("export:" + principal.orgId(), 2);
        audit.recordIndependent(principal.orgId(), principal.userId(), AuditAction.DATA_EXPORTED, "WORKSPACE",
                principal.orgId(), Map.of("scope", "workspace"));
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"pitsch-workspace-" + principal.orgId() + "-" + LocalDate.now() + ".zip\"");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        exports.exportWorkspace(principal.orgId(), response.getOutputStream());
    }

    @PostMapping("/api/v1/workspace/delete")
    @RequiresPermission(Permission.ORG_DELETE)
    public ResponseEntity<Map<String, Object>> deleteWorkspace(AuthPrincipal principal,
                                                               @RequestBody DeleteWorkspaceRequest req) {
        limiter.check("danger:" + principal.userId(), 5);
        deletion.requestWorkspaceDeletion(principal, req.confirmName(), req.password());
        return ResponseEntity.accepted().body(Map.of("status", "DELETION_SCHEDULED"));
    }

    @GetMapping("/api/v1/me/export")
    @AllowWithoutWorkspace
    @AllowUnverified
    public ResponseEntity<Map<String, Object>> exportMe(AuthPrincipal principal) {
        limiter.check("export-me:" + principal.userId(), 3);
        audit.recordIndependent(principal.organizationId(), principal.userId(), AuditAction.DATA_EXPORTED, "USER",
                principal.userId(), Map.of("scope", "account"));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"pitsch-account-" + LocalDate.now() + ".json\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(exports.exportUser(principal.userId()));
    }

    @PostMapping("/api/v1/me/delete")
    @AllowWithoutWorkspace
    @AllowUnverified
    public ResponseEntity<Map<String, Object>> deleteMe(AuthPrincipal principal, @RequestBody DeleteAccountRequest req) {
        limiter.check("danger:" + principal.userId(), 5);
        if (!"DELETE".equals(req.confirm())) {
            throw ApiException.badRequest("Type DELETE to confirm account deletion.");
        }
        deletion.deleteAccount(principal, req.password());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
                .body(Map.of("status", "DELETED"));
    }
}
