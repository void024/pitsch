package com.pitsch.backend.workflow;

import java.time.Clock;
import java.util.List;
import java.util.function.Consumer;

import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.Json;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persists workflow state changes. Each change is a short transaction (state is saved after every step) protected by
 * optimistic locking (@Version) with a bounded re-read/re-apply loop, replacing the old in-memory per-process locks.
 */
@Component
public class WorkflowStore {

    /** Thrown inside background steps when the user stopped the workflow meanwhile. */
    public static final class StoppedException extends RuntimeException {
        public StoppedException() {
            super("Workflow was stopped");
        }
    }

    private static final int MAX_RETRIES = 5;

    private final WorkflowRepository workflows;
    private final TransactionTemplate tx;
    private final Clock clock;

    public WorkflowStore(WorkflowRepository workflows, PlatformTransactionManager txManager, Clock clock) {
        this.workflows = workflows;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Workflow load(Long id) {
        return workflows.findById(id).orElseThrow(() -> ApiException.notFound("Workflow"));
    }

    /** Load–modify–save with retry on concurrent modification; aborts if the user stopped the workflow. */
    public Workflow update(Long workflowId, Consumer<Workflow> change) {
        return update(workflowId, true, change);
    }

    public Workflow update(Long workflowId, boolean abortIfStopped, Consumer<Workflow> change) {
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(s -> {
                    Workflow wf = load(workflowId);
                    if (abortIfStopped && WorkflowStatus.STOPPED.equals(wf.getStatus())) {
                        throw new StoppedException();
                    }
                    String before = wf.getStatus();
                    change.accept(wf);
                    if (!before.equals(wf.getStatus())) {
                        WorkflowStatus.requireTransition(before, wf.getStatus());
                        wf.setLastTransitionAt(clock.instant());
                    }
                    return workflows.saveAndFlush(wf);
                });
            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt >= MAX_RETRIES) {
                    throw e;
                }
            }
        }
    }

    /** Puts the workflow in a waiting state with the given step and offered actions. */
    public static void waitForUser(Workflow w, String step, List<String> actions, String status) {
        w.setStatus(status == null ? WorkflowStatus.WAITING_FOR_APPROVAL : status);
        w.setCurrentStep(step);
        w.setAvailableActions(Json.joinCsv(actions));
        w.setError(null);
    }

    public static void processing(Workflow w, String step) {
        w.setStatus(WorkflowStatus.PROCESSING);
        w.setCurrentStep(step);
        w.setAvailableActions("STOP");
        w.setError(null);
    }
}
