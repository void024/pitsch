package com.pitsch.backend.integration;

import java.net.URI;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.pitsch.backend.auth.AuthPrincipal;
import com.pitsch.backend.auth.Permission;
import com.pitsch.backend.auth.PublicEndpoint;
import com.pitsch.backend.auth.RequiresPermission;
import com.pitsch.backend.auth.SessionCookies;
import com.pitsch.backend.common.ClientInfo;
import com.pitsch.backend.common.Pagination;
import com.pitsch.backend.jobs.JobQueue;
import com.pitsch.backend.jobs.JobType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IntegrationController {

    public record ConnectRequest(@NotEmpty List<String> integrations, @Size(max = 100) String returnPath) { }

    private final IntegrationService service;
    private final IntegrationConnectionRepository connections;
    private final SessionCookies cookies;
    private final JobQueue jobs;

    public IntegrationController(IntegrationService service, IntegrationConnectionRepository connections,
                                 SessionCookies cookies, JobQueue jobs) {
        this.service = service;
        this.connections = connections;
        this.cookies = cookies;
        this.jobs = jobs;
    }

    @GetMapping("/api/v1/integrations")
    public List<IntegrationService.IntegrationStatus> list(AuthPrincipal principal) {
        return service.statuses(principal);
    }

    /** Returns the Google consent URL; the browser navigates there (top-level) and comes back to /callback. */
    @PostMapping("/api/v1/integrations/connect")
    @RequiresPermission(Permission.INTEGRATION_CONNECT)
    public Map<String, String> connect(AuthPrincipal principal, @Valid @RequestBody ConnectRequest req) {
        Set<Integration> wanted = EnumSet.noneOf(Integration.class);
        for (String i : req.integrations()) {
            wanted.add(Pagination.enumParam(Integration.class, i, "integrations"));
        }
        return Map.of("authorizationUrl", service.startConnect(principal, wanted, req.returnPath()));
    }

    @PublicEndpoint
    @GetMapping("/api/v1/integrations/google/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error) {
        IntegrationService.CallbackResult result = service.completeCallback(code, state, error, ClientInfo.current());
        ResponseEntity.BodyBuilder redirect = ResponseEntity.status(HttpStatus.FOUND).location(URI.create(result.redirectUrl()));
        if (result.session() != null) {
            redirect.header(HttpHeaders.SET_COOKIE, cookies.refresh(result.session().refreshToken()).toString());
        }
        return redirect.build();
    }

    @PublicEndpoint
    @GetMapping("/api/v1/auth/google/start")
    public Map<String, String> googleLogin(@RequestParam(required = false) String returnPath) {
        return Map.of("authorizationUrl", service.startLogin(returnPath));
    }

    @DeleteMapping("/api/v1/integrations/{integration}")
    @RequiresPermission(Permission.INTEGRATION_CONNECT)
    public ResponseEntity<Void> disconnect(AuthPrincipal principal, @PathVariable String integration) {
        service.disconnect(principal, Pagination.enumParam(Integration.class, integration, "integration"));
        return ResponseEntity.noContent().build();
    }

    /** Triggers an immediate Gmail sync for the caller's mailbox (queued; rate-limited by the job dedupe window). */
    @PostMapping("/api/v1/integrations/gmail/sync")
    @RequiresPermission(Permission.EMAIL_IMPORT)
    public ResponseEntity<Map<String, Object>> syncNow(AuthPrincipal principal) {
        IntegrationConnection c = service.require(principal.orgId(), principal.userId(), Integration.GMAIL);
        long minute = System.currentTimeMillis() / 60_000;
        jobs.enqueue(JobType.GMAIL_SYNC, principal.orgId(), null, Map.of("connectionId", c.getId()),
                "gmail-sync-manual:" + c.getId() + ":" + minute, java.time.Duration.ZERO);
        return ResponseEntity.accepted().body(Map.of("queued", true));
    }
}
