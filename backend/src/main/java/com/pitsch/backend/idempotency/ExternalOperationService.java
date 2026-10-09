package com.pitsch.backend.idempotency;

import java.time.Clock;

import com.pitsch.backend.common.Json;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exactly-once bookkeeping for external side effects. Callers:
 * <pre>
 *   op = begin(key)            // SUCCEEDED -> skip, return stored external ID
 *   if op was PENDING before   // a previous attempt may have reached the provider: reconcile first
 *   id = provider.call(...)    // provider call is itself keyed by the same idempotency key
 *   succeed(key, id)
 * </pre>
 * Each step commits on its own so a crash between the provider call and {@code succeed} leaves a PENDING row that
 * the retry reconciles instead of repeating blindly.
 */
@Service
public class ExternalOperationService {

    public record Begin(ExternalOperation operation, boolean alreadySucceeded, boolean resumedPending) { }

    private final ExternalOperationRepository repo;
    private final Clock clock;

    public ExternalOperationService(ExternalOperationRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Begin begin(Long orgId, String key, String type, Long workflowId) {
        ExternalOperation existing = repo.findByIdempotencyKey(key).orElse(null);
        if (existing == null) {
            ExternalOperation op = new ExternalOperation();
            op.setOrganizationId(orgId);
            op.setIdempotencyKey(key);
            op.setOperationType(type);
            op.setStatus(ExternalOperation.Status.PENDING.name());
            op.setWorkflowId(workflowId);
            op.setAttempts(1);
            op.setCreatedAt(clock.instant());
            op.setUpdatedAt(clock.instant());
            try {
                return new Begin(repo.saveAndFlush(op), false, false);
            } catch (DataIntegrityViolationException race) {
                existing = repo.findByIdempotencyKey(key).orElseThrow();
            }
        }
        if (ExternalOperation.Status.SUCCEEDED.name().equals(existing.getStatus())) {
            return new Begin(existing, true, false);
        }
        boolean wasPending = ExternalOperation.Status.PENDING.name().equals(existing.getStatus());
        existing.setStatus(ExternalOperation.Status.PENDING.name());
        existing.setAttempts(existing.getAttempts() + 1);
        existing.setUpdatedAt(clock.instant());
        return new Begin(repo.save(existing), false, wasPending);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(String key, String externalId) {
        repo.findByIdempotencyKey(key).ifPresent(op -> {
            op.setStatus(ExternalOperation.Status.SUCCEEDED.name());
            op.setExternalId(externalId);
            op.setError(null);
            op.setUpdatedAt(clock.instant());
            repo.save(op);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(String key, String error) {
        repo.findByIdempotencyKey(key).ifPresent(op -> {
            op.setStatus(ExternalOperation.Status.FAILED.name());
            op.setError(Json.truncate(error, 1000));
            op.setUpdatedAt(clock.instant());
            repo.save(op);
        });
    }
}
