package com.pitsch.backend.approval;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.pitsch.backend.audit.AuditAction;
import com.pitsch.backend.audit.AuditService;
import com.pitsch.backend.auth.AuthPrincipal;
import com.pitsch.backend.auth.Permission;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.ErrorCode;
import com.pitsch.backend.common.Hashing;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.jobs.JobQueue;
import com.pitsch.backend.jobs.JobType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates, decides and tracks approvals. Execution happens in the EXECUTE_APPROVAL job (ActionService). */
@Service
public class ApprovalService {

    private final ApprovalRepository approvals;
    private final JobQueue jobs;
    private final AuditService audit;
    private final Json json;
    private final Clock clock;

    public ApprovalService(ApprovalRepository approvals, JobQueue jobs, AuditService audit, Json json, Clock clock) {
        this.approvals = approvals;
        this.jobs = jobs;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    /**
     * AI/system proposal awaiting a human decision. For pipeline syncs, an older pending proposal for the same pitch is
     * superseded so the approval inbox shows only the latest state.
     */
    @Transactional
    public Approval propose(Long orgId, Long workflowId, Long pitchId, ApprovalType type, String summary,
                            JsonNode payload, String reason, String idempotencyKey) {
        Approval existing = approvals.findByIdempotencyKey(Json.truncate(idempotencyKey, 200)).orElse(null);
        if (existing != null) {
            return existing;   // the same event was already proposed (job retry)
        }
        if (pitchId != null && type == ApprovalType.SYNC_PIPELINE) {
            for (Approval old : approvals.findByOrganizationIdAndPitchIdAndActionTypeAndStatus(orgId, pitchId, type.name(),
                    Approval.Status.PENDING.name())) {
                old.setStatus(Approval.Status.SUPERSEDED.name());
                approvals.save(old);
            }
        }
        Approval a = build(orgId, workflowId, pitchId, type, summary, payload, reason, idempotencyKey, "AI");
        a.setStatus(Approval.Status.PENDING.name());
        a = approvals.save(a);
        audit.recordAi(orgId, AuditAction.APPROVAL_REQUESTED, "APPROVAL", a.getId(), Map.of("type", type.name()));
        return a;
    }

    /** The user's explicit action (clicking Send / picking a slot) is itself the approval. Execution is queued. */
    @Transactional
    public Approval approveNow(AuthPrincipal principal, Long workflowId, Long pitchId, ApprovalType type, String summary,
                               JsonNode payload, String idempotencyKey) {
        principal.require(Permission.ACTION_APPROVE);
        Approval a = build(principal.orgId(), workflowId, pitchId, type, summary, payload, null, idempotencyKey, "USER");
        a.setStatus(Approval.Status.APPROVED.name());
        a.setDecidedByUserId(principal.userId());
        a.setDecidedAt(clock.instant());
        a = approvals.save(a);
        audit.record(principal.orgId(), principal.userId(), AuditAction.USER_APPROVED, "APPROVAL", a.getId(),
                Map.of("type", type.name(), "workflowId", workflowId == null ? "" : workflowId));
        jobs.enqueue(JobType.EXECUTE_APPROVAL, principal.orgId(), workflowId, Map.of("approvalId", a.getId()));
        return a;
    }

    @Transactional
    public Approval approve(AuthPrincipal principal, Long approvalId) {
        principal.require(Permission.ACTION_APPROVE);
        Approval a = approvals.findByIdAndOrganizationId(approvalId, principal.orgId())
                .orElseThrow(() -> ApiException.notFound("Approval"));
        if (!Approval.Status.PENDING.name().equals(a.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "This request was already " + a.getStatus().toLowerCase() + ".");
        }
        a.setStatus(Approval.Status.APPROVED.name());
        a.setDecidedByUserId(principal.userId());
        a.setDecidedAt(clock.instant());
        approvals.save(a);
        audit.record(principal.orgId(), principal.userId(), AuditAction.USER_APPROVED, "APPROVAL", a.getId(),
                Map.of("type", a.getActionType()));
        jobs.enqueue(JobType.EXECUTE_APPROVAL, principal.orgId(), a.getWorkflowId(), Map.of("approvalId", a.getId()));
        return a;
    }

    @Transactional
    public Approval reject(AuthPrincipal principal, Long approvalId, String reason) {
        principal.require(Permission.ACTION_APPROVE);
        Approval a = approvals.findByIdAndOrganizationId(approvalId, principal.orgId())
                .orElseThrow(() -> ApiException.notFound("Approval"));
        if (!Approval.Status.PENDING.name().equals(a.getStatus())) {
            throw new ApiException(ErrorCode.CONFLICT, "This request was already " + a.getStatus().toLowerCase() + ".");
        }
        a.setStatus(Approval.Status.REJECTED.name());
        a.setDecidedByUserId(principal.userId());
        a.setDecidedAt(clock.instant());
        a.setReason(reason == null ? null : Json.truncate(reason, 1000));
        approvals.save(a);
        audit.record(principal.orgId(), principal.userId(), AuditAction.USER_REJECTED, "APPROVAL", a.getId(),
                Map.of("type", a.getActionType()));
        return a;
    }

    /** Re-checks at execution time that the approved payload was not altered. */
    public JsonNode verifiedPayload(Approval a) {
        if (!Hashing.sha256Hex(a.getPayloadJson()).equals(a.getPayloadHash())) {
            throw new IllegalStateException("Approval " + a.getId() + " payload does not match its approved hash");
        }
        return json.read(a.getPayloadJson());
    }

    @Transactional(readOnly = true)
    public List<Approval> forWorkflow(Long workflowId) {
        return approvals.findByWorkflowIdOrderByIdDesc(workflowId);
    }

    private Approval build(Long orgId, Long workflowId, Long pitchId, ApprovalType type, String summary, JsonNode payload,
                           String reason, String idempotencyKey, String proposedBy) {
        String payloadJson = json.write(payload);
        Approval a = new Approval();
        a.setOrganizationId(orgId);
        a.setWorkflowId(workflowId);
        a.setPitchId(pitchId);
        a.setActionType(type.name());
        a.setSummary(Json.truncate(summary, 500));
        a.setPayloadJson(payloadJson);
        a.setPayloadHash(Hashing.sha256Hex(payloadJson));
        a.setProposedBy(proposedBy);
        a.setReason(reason == null ? null : Json.truncate(reason, 1000));
        a.setIdempotencyKey(Json.truncate(idempotencyKey, 200));
        return a;
    }
}
