package com.pitsch.backend.jobs;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import com.pitsch.backend.common.Json;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Enqueues jobs in the caller's transaction (transactional outbox): if the business change rolls back, the job
 * disappears with it. In inline mode (tests) the job runs right after the commit on a separate thread, synchronously.
 */
@Service
public class JobQueue {

    private final JobRepository jobs;
    private final JobRunner runner;
    private final Json json;
    private final Clock clock;

    public JobQueue(JobRepository jobs, JobRunner runner, Json json, Clock clock) {
        this.jobs = jobs;
        this.runner = runner;
        this.json = json;
        this.clock = clock;
    }

    public Job enqueue(JobType type, Long orgId, Long workflowId, Map<String, ?> payload) {
        return enqueue(type, orgId, workflowId, payload, null, Duration.ZERO);
    }

    /**
     * @param dedupeKey if non-null and a job with this key already exists, nothing is enqueued and null is returned
     */
    public Job enqueue(JobType type, Long orgId, Long workflowId, Map<String, ?> payload, String dedupeKey, Duration delay) {
        if (dedupeKey != null && jobs.existsByDedupeKey(dedupeKey)) {
            return null;
        }
        Job j = new Job();
        j.setType(type.name());
        j.setOrganizationId(orgId);
        j.setWorkflowId(workflowId);
        j.setPayloadJson(payload == null ? "{}" : json.write(payload));
        j.setStatus(Job.Status.QUEUED.name());
        j.setPriority(type == JobType.EXECUTE_APPROVAL ? 10 : type == JobType.GMAIL_SYNC ? 0 : 5);
        j.setMaxAttempts(type.maxAttempts());
        j.setRunAt(clock.instant().plus(delay));
        j.setDedupeKey(dedupeKey);
        j.setCreatedAt(clock.instant());
        j.setUpdatedAt(clock.instant());
        Job saved;
        try {
            saved = jobs.save(j);
        } catch (DataIntegrityViolationException e) {
            return null;   // concurrent enqueue with the same dedupe key
        }
        if (runner.isInline() && delay.isZero()) {
            Long id = saved.getId();
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        runner.runInlineNow(id);
                    }
                });
            } else {
                runner.runInlineNow(id);
            }
        }
        return saved;
    }
}
