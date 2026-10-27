package com.pitsch.backend.notification;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.pitsch.backend.auth.AuthPrincipal;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.PageResponse;
import com.pitsch.backend.common.Pagination;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NotificationController {

    public record NotificationView(Long id, String type, String title, String message, Long workflowId, boolean read,
                                   Instant createdAt) { }

    public record PreferenceView(String type, boolean inApp, boolean email) { }

    public record PreferenceUpdate(@NotNull List<PreferenceView> preferences) { }

    private final NotificationRepository repo;
    private final NotificationService service;
    private final Clock clock;

    public NotificationController(NotificationRepository repo, NotificationService service, Clock clock) {
        this.repo = repo;
        this.service = service;
        this.clock = clock;
    }

    /** Legacy: latest 50. */
    @GetMapping("/api/notifications")
    public List<NotificationView> latest(AuthPrincipal principal) {
        return repo.findTop50ByUserIdAndOrganizationIdOrderByCreatedAtDesc(principal.userId(), principal.orgId())
                .stream().map(NotificationController::view).toList();
    }

    @GetMapping("/api/v1/notifications")
    public PageResponse<NotificationView> page(AuthPrincipal principal, @RequestParam(required = false) Boolean unread,
                                               @RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size) {
        Pageable p = Pagination.of(page, size, null, Map.of("createdAt", "createdAt"), "createdAt,desc");
        return PageResponse.of(Boolean.TRUE.equals(unread)
                ? repo.findByUserIdAndOrganizationIdAndReadFalse(principal.userId(), principal.orgId(), p)
                : repo.findByUserIdAndOrganizationId(principal.userId(), principal.orgId(), p), NotificationController::view);
    }

    @GetMapping("/api/v1/notifications/unread-count")
    public Map<String, Long> unreadCount(AuthPrincipal principal) {
        return Map.of("count", repo.countByUserIdAndOrganizationIdAndReadFalse(principal.userId(), principal.orgId()));
    }

    @PatchMapping({"/api/notifications/{id}/read", "/api/v1/notifications/{id}/read"})
    public NotificationView markRead(AuthPrincipal principal, @PathVariable Long id) {
        Notification n = repo.findByIdAndUserIdAndOrganizationId(id, principal.userId(), principal.orgId())
                .orElseThrow(() -> ApiException.notFound("Notification"));
        if (!n.isRead()) {
            n.setRead(true);
            n.setReadAt(clock.instant());
            repo.save(n);
        }
        return view(n);
    }

    @PatchMapping({"/api/notifications/read-all", "/api/v1/notifications/read-all"})
    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> markAllRead(AuthPrincipal principal) {
        return Map.of("updated", repo.markAllRead(principal.userId(), principal.orgId(), clock.instant()));
    }

    @GetMapping("/api/v1/notifications/preferences")
    public List<PreferenceView> preferences(AuthPrincipal principal) {
        Map<NotificationType, NotificationPreference> stored = service.preferences(principal.userId(), principal.orgId());
        List<PreferenceView> out = new ArrayList<>();
        for (NotificationType t : NotificationType.values()) {
            NotificationPreference p = stored.get(t);
            out.add(new PreferenceView(t.name(), p == null || p.isInApp(),
                    p == null ? NotificationType.EMAIL_BY_DEFAULT.contains(t) : p.isEmail()));
        }
        return out;
    }

    @PutMapping("/api/v1/notifications/preferences")
    public List<PreferenceView> updatePreferences(AuthPrincipal principal, @RequestBody PreferenceUpdate req) {
        for (PreferenceView p : req.preferences()) {
            NotificationType type = Pagination.enumParam(NotificationType.class, p.type(), "type");
            if (type != null) {
                service.setPreference(principal.userId(), principal.orgId(), type, p.inApp(), p.email());
            }
        }
        return preferences(principal);
    }

    @PostMapping("/api/v1/notifications/test")
    public Map<String, Object> test(AuthPrincipal principal) {
        service.notify(principal.orgId(), principal.userId(), NotificationType.WORKFLOW_COMPLETED, "Test notification",
                "Notifications are working.", null, null);
        return Map.of("ok", true);
    }

    static NotificationView view(Notification n) {
        return new NotificationView(n.getId(), n.getType(), n.getTitle(), n.getMessage(), n.getWorkflowId(), n.isRead(),
                n.getCreatedAt());
    }
}
