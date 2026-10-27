package com.pitsch.backend.activity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.pitsch.backend.auth.AuthPrincipal;
import com.pitsch.backend.auth.Permission;
import com.pitsch.backend.auth.RequiresPermission;
import com.pitsch.backend.common.PageResponse;
import com.pitsch.backend.common.Pagination;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ActivityController {

    public record ActivityView(Long id, String message, String type, Long userId, Instant createdAt) { }

    private final ActivityRepository repo;

    public ActivityController(ActivityRepository repo) {
        this.repo = repo;
    }

    /** Legacy: latest 50 entries (the original frontend contract). */
    @GetMapping("/api/activity")
    @RequiresPermission(Permission.PITCH_READ)
    public List<ActivityView> latest(AuthPrincipal principal) {
        return repo.findTop50ByOrganizationIdOrderByCreatedAtDesc(principal.orgId()).stream().map(ActivityController::view).toList();
    }

    @GetMapping("/api/v1/activity")
    @RequiresPermission(Permission.PITCH_READ)
    public PageResponse<ActivityView> page(AuthPrincipal principal, @RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size) {
        return PageResponse.of(repo.findByOrganizationId(principal.orgId(),
                Pagination.of(page, size, null, Map.of("createdAt", "createdAt"), "createdAt,desc")), ActivityController::view);
    }

    static ActivityView view(Activity a) {
        return new ActivityView(a.getId(), a.getMessage(), a.getType(), a.getUserId(), a.getCreatedAt());
    }
}
