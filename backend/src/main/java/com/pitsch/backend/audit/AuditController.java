package com.pitsch.backend.audit;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.pitsch.backend.auth.AuthPrincipal;
import com.pitsch.backend.auth.Permission;
import com.pitsch.backend.auth.RequiresPermission;
import com.pitsch.backend.auth.User;
import com.pitsch.backend.auth.UserRepository;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.common.PageResponse;
import com.pitsch.backend.common.Pagination;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only access to the workspace audit log (OWNER/ADMIN). There is deliberately no write/update/delete API. */
@RestController
@RequestMapping("/api/v1/audit-events")
public class AuditController {

    public record AuditView(Long id, String action, String actorType, Long actorUserId, String actorName,
                            String resourceType, String resourceId, String ipAddress, String requestId,
                            JsonNode metadata, Instant createdAt) { }

    private final AuditEventRepository repo;
    private final UserRepository users;
    private final Json json;

    public AuditController(AuditEventRepository repo, UserRepository users, Json json) {
        this.repo = repo;
        this.users = users;
        this.json = json;
    }

    @GetMapping
    @RequiresPermission(Permission.AUDIT_READ)
    public PageResponse<AuditView> list(AuthPrincipal principal,
                                        @RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size,
                                        @RequestParam(required = false) String action,
                                        @RequestParam(required = false) Long actorUserId,
                                        @RequestParam(required = false) String resourceType,
                                        @RequestParam(required = false) String resourceId,
                                        @RequestParam(required = false) String from,
                                        @RequestParam(required = false) String to) {
        Long orgId = principal.orgId();
        String actionFilter = action == null || action.isBlank() ? null
                : Pagination.enumParam(AuditAction.class, action, "action").name();
        Instant fromTs = parse(from, "from");
        Instant toTs = parse(to, "to");
        Specification<AuditEvent> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("organizationId"), orgId));
            if (actionFilter != null) {
                p.add(cb.equal(root.get("action"), actionFilter));
            }
            if (actorUserId != null) {
                p.add(cb.equal(root.get("actorUserId"), actorUserId));
            }
            if (resourceType != null && !resourceType.isBlank()) {
                p.add(cb.equal(root.get("resourceType"), resourceType.trim().toUpperCase()));
            }
            if (resourceId != null && !resourceId.isBlank()) {
                p.add(cb.equal(root.get("resourceId"), resourceId.trim()));
            }
            if (fromTs != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("createdAt"), fromTs));
            }
            if (toTs != null) {
                p.add(cb.lessThan(root.get("createdAt"), toTs));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        Pageable pageable = Pagination.of(page, size, null, Map.of("createdAt", "createdAt"), "createdAt,desc");
        Page<AuditEvent> result = repo.findAll(spec, pageable);
        Map<Long, String> names = users.findAllById(result.getContent().stream()
                        .map(AuditEvent::getActorUserId).filter(java.util.Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, User::getName, (a, b) -> a));
        Function<AuditEvent, AuditView> view = e -> new AuditView(e.getId(), e.getAction(), e.getActorType(),
                e.getActorUserId(), e.getActorUserId() == null ? null : names.get(e.getActorUserId()),
                e.getResourceType(), e.getResourceId(), e.getIpAddress(), e.getRequestId(),
                json.read(e.getMetadataJson()), e.getCreatedAt());
        return PageResponse.of(result, view);
    }

    private static Instant parse(String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest(name + " must be an ISO-8601 instant");
        }
    }
}
