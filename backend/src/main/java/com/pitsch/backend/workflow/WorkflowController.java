package com.pitsch.backend.workflow;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.pitsch.backend.approval.ApprovalController;
import com.pitsch.backend.approval.ApprovalService;
import com.pitsch.backend.auth.AuthPrincipal;
import com.pitsch.backend.auth.Permission;
import com.pitsch.backend.auth.RequiresPermission;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.PageResponse;
import com.pitsch.backend.common.Pagination;
import com.pitsch.backend.common.RateLimiter;
import com.pitsch.backend.config.PitschProperties;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WorkflowController {

    public record ExecutionView(Long id, String agentName, int step, String status, String model, int attempts,
                                long promptTokens, long completionTokens, java.math.BigDecimal estimatedCostUsd,
                                long latencyMs, String errorCode, String errorCategory, String errorMessage,
                                String inputHash, Instant startedAt, Instant completedAt) { }

    private static final Map<String, String> SORTABLE = Map.of("createdAt", "createdAt", "updatedAt", "updatedAt",
            "status", "status");

    private final WorkflowRepository workflows;
    private final WorkflowEngine engine;
    private final WorkflowViews views;
    private final EmailDraftRepository drafts;
    private final AgentExecutionRepository executions;
    private final ApprovalService approvals;
    private final ApprovalController approvalViews;
    private final RateLimiter limiter;
    private final PitschProperties props;

    public WorkflowController(WorkflowRepository workflows, WorkflowEngine engine, WorkflowViews views,
                              EmailDraftRepository drafts, AgentExecutionRepository executions, ApprovalService approvals,
                              ApprovalController approvalViews, RateLimiter limiter, PitschProperties props) {
        this.workflows = workflows;
        this.engine = engine;
        this.views = views;
        this.drafts = drafts;
        this.executions = executions;
        this.approvals = approvals;
        this.approvalViews = approvalViews;
        this.limiter = limiter;
        this.props = props;
    }

    /** Legacy: newest 200 workflows as a plain array (original frontend contract). */
    @GetMapping("/api/workflows")
    @RequiresPermission(Permission.PITCH_READ)
    public List<WorkflowViews.WorkflowView> legacyList(AuthPrincipal principal) {
        Long org = principal.orgId();
        Page<Workflow> page = workflows.findAll((root, q, cb) -> cb.equal(root.get("organizationId"), org),
                PageRequest.of(0, 200, Sort.by(Sort.Direction.DESC, "createdAt")));
        return views.summaries(page.getContent());
    }

    @GetMapping("/api/v1/workflows")
    @RequiresPermission(Permission.PITCH_READ)
    public PageResponse<WorkflowViews.WorkflowView> list(AuthPrincipal principal,
                                                         @RequestParam(required = false) String status,
                                                         @RequestParam(required = false) String type,
                                                         @RequestParam(required = false) Boolean needsReview,
                                                         @RequestParam(required = false) Boolean needsAction,
                                                         @RequestParam(required = false) Long pitchId,
                                                         @RequestParam(required = false) String q,
                                                         @RequestParam(required = false) Integer page,
                                                         @RequestParam(required = false) Integer size,
                                                         @RequestParam(required = false) String sort) {
        Long org = principal.orgId();
        List<String> statuses = status == null || status.isBlank() ? List.of()
                : java.util.Arrays.stream(status.split(",")).map(String::trim).map(String::toUpperCase)
                .filter(WorkflowStatus.ALL::contains).toList();
        String typeFilter = Pagination.oneOf(type, java.util.Set.of("NEW_PITCH", "FOLLOW_UP", "NOT_PITCH"), "type");
        String search = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase().replace("%", "\\%") + "%";
        Specification<Workflow> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("organizationId"), org));
            if (!statuses.isEmpty()) {
                p.add(root.get("status").in(statuses));
            }
            if (Boolean.TRUE.equals(needsAction)) {
                p.add(root.get("status").in(WorkflowStatus.NEEDS_USER));
            }
            if (typeFilter != null) {
                p.add(cb.equal(root.get("type"), typeFilter));
            }
            if (needsReview != null) {
                p.add(cb.equal(root.get("needsHumanReview"), needsReview));
            }
            if (pitchId != null) {
                p.add(cb.equal(root.get("pitchId"), pitchId));
            }
            if (search != null) {
                p.add(cb.like(cb.lower(root.get("summary")), search));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        Pageable pageable = Pagination.of(page, size, sort, SORTABLE, "createdAt,desc");
        Page<Workflow> result = workflows.findAll(spec, pageable);
        return new PageResponse<>(views.summaries(result.getContent()), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @GetMapping({"/api/workflows/{id}", "/api/v1/workflows/{id}"})
    @RequiresPermission(Permission.PITCH_READ)
    public WorkflowViews.WorkflowView get(AuthPrincipal principal, @PathVariable Long id) {
        return views.detail(find(principal, id));
    }

    /** Body: {"action": "COMPLETE_WORKFLOW" | "STOP" | "PLAN_MEETING" | "PLAN_EMAIL_RESPONSE" | "RETRY", ...} */
    @PostMapping({"/api/workflows/{id}/action", "/api/v1/workflows/{id}/actions"})
    @RequiresPermission(Permission.WORKFLOW_RUN)
    public ResponseEntity<WorkflowViews.WorkflowView> action(AuthPrincipal principal, @PathVariable Long id,
                                                             @RequestBody ActionRequest req) {
        limiter.check("ai:" + principal.userId(), props.getRateLimit().getAiPerMinute());
        Workflow wf = engine.applyAction(principal, id, req);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(views.detail(wf));
    }

    @GetMapping({"/api/workflows/{id}/draft", "/api/v1/workflows/{id}/draft"})
    @RequiresPermission(Permission.PITCH_READ)
    public WorkflowViews.DraftView draft(AuthPrincipal principal, @PathVariable Long id) {
        find(principal, id);
        return drafts.findFirstByWorkflowIdOrderByIdDesc(id).map(views::draftView)
                .orElseThrow(() -> ApiException.notFound("Draft"));
    }

    /** Agent call log for this workflow: model, tokens, cost, latency, errors (inputs are not returned). */
    @GetMapping({"/api/workflows/{id}/executions", "/api/v1/workflows/{id}/executions"})
    @RequiresPermission(Permission.PITCH_READ)
    public List<ExecutionView> executions(AuthPrincipal principal, @PathVariable Long id) {
        find(principal, id);
        return executions.findByWorkflowIdOrderByIdAsc(id).stream().map(e -> new ExecutionView(e.getId(), e.getAgentName(),
                e.getStep(), e.getStatus(), e.getModel(), e.getAttempts(), e.getPromptTokens(), e.getCompletionTokens(),
                e.getEstimatedCostUsd(), e.getLatencyMs(), e.getErrorCode(), e.getErrorCategory(), e.getErrorMessage(),
                e.getInputHash(), e.getStartedAt(), e.getCompletedAt())).toList();
    }

    @GetMapping("/api/v1/workflows/{id}/approvals")
    @RequiresPermission(Permission.PITCH_READ)
    public List<ApprovalController.ApprovalView> approvals(AuthPrincipal principal, @PathVariable Long id) {
        find(principal, id);
        return approvals.forWorkflow(id).stream().map(approvalViews::view).toList();
    }

    /** The investment brief as a Markdown file (download / sharing). */
    @GetMapping(value = {"/api/workflows/{id}/brief.md", "/api/v1/workflows/{id}/brief.md"}, produces = "text/markdown")
    @RequiresPermission(Permission.PITCH_READ)
    public ResponseEntity<String> briefMarkdown(AuthPrincipal principal, @PathVariable Long id) {
        Workflow wf = find(principal, id);
        if (wf.getBriefMarkdown() == null) {
            throw ApiException.notFound("Brief");
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
                .header("Content-Disposition", "attachment; filename=\"brief-" + id + ".md\"")
                .body(wf.getBriefMarkdown());
    }

    private Workflow find(AuthPrincipal principal, Long id) {
        return workflows.findByIdAndOrganizationId(id, principal.orgId()).orElseThrow(() -> ApiException.notFound("Workflow"));
    }
}
