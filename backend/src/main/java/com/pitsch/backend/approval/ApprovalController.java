package com.pitsch.backend.approval;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.pitsch.backend.auth.AuthPrincipal;
import com.pitsch.backend.auth.Permission;
import com.pitsch.backend.auth.RequiresPermission;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.common.PageResponse;
import com.pitsch.backend.common.Pagination;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The approvals inbox: AI-proposed external actions waiting for a human decision, plus their history. */
@RestController
public class ApprovalController {

    public record ApprovalView(Long id, String type, String status, String summary, JsonNode payload, Long workflowId,
                               Long pitchId, String proposedBy, String reason, Long decidedByUserId, Instant decidedAt,
                               Instant executedAt, String error, Instant createdAt) { }

    public record RejectRequest(@Size(max = 1000) String reason) { }

    private final ApprovalService service;
    private final ApprovalRepository repo;
    private final Json json;

    public ApprovalController(ApprovalService service, ApprovalRepository repo, Json json) {
        this.service = service;
        this.repo = repo;
        this.json = json;
    }

    @GetMapping("/api/v1/approvals")
    @RequiresPermission(Permission.PITCH_READ)
    public PageResponse<ApprovalView> list(AuthPrincipal principal, @RequestParam(required = false) String status,
                                           @RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size) {
        Pageable pageable = Pagination.of(page, size, null, Map.of("createdAt", "createdAt"), "createdAt,desc");
        String s = status == null || status.isBlank() ? null
                : Pagination.enumParam(Approval.Status.class, status, "status").name();
        return PageResponse.of(s == null ? repo.findByOrganizationId(principal.orgId(), pageable)
                : repo.findByOrganizationIdAndStatus(principal.orgId(), s, pageable), this::view);
    }

    @PostMapping("/api/v1/approvals/{id}/approve")
    @RequiresPermission(Permission.ACTION_APPROVE)
    public ApprovalView approve(AuthPrincipal principal, @PathVariable Long id) {
        return view(service.approve(principal, id));
    }

    @PostMapping("/api/v1/approvals/{id}/reject")
    @RequiresPermission(Permission.ACTION_APPROVE)
    public ApprovalView reject(AuthPrincipal principal, @PathVariable Long id, @RequestBody(required = false) RejectRequest req) {
        return view(service.reject(principal, id, req == null ? null : req.reason()));
    }

    public ApprovalView view(Approval a) {
        return new ApprovalView(a.getId(), a.getActionType(), a.getStatus(), a.getSummary(), json.read(a.getPayloadJson()),
                a.getWorkflowId(), a.getPitchId(), a.getProposedBy(), a.getReason(), a.getDecidedByUserId(),
                a.getDecidedAt(), a.getExecutedAt(), a.getError(), a.getCreatedAt());
    }
}
