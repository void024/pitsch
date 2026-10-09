package com.pitsch.backend.workflow;

import java.util.Map;
import java.util.Set;

import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.ErrorCode;

/**
 * Workflow states and the only allowed transitions between them. Every status change goes through
 * {@link #requireTransition}; an illegal transition is a bug or a race and is refused.
 *
 * <pre>
 * RECEIVED ─► CLASSIFYING ─┬─► NOT_PITCH ──(handle anyway)──► PROCESSING
 *                          └─► AWAITING_USER ─► PROCESSING ─► WAITING_FOR_APPROVAL ─► COMPLETED
 *                                    │               ▲   │               │
 *                                    └───────────────┘   └──► FAILED ─(retry)─┘
 * any non-final state ─► STOPPED
 * </pre>
 */
public final class WorkflowStatus {
    public static final String RECEIVED = "RECEIVED";
    public static final String CLASSIFYING = "CLASSIFYING";
    public static final String NOT_PITCH = "NOT_PITCH";
    public static final String AWAITING_USER = "AWAITING_USER";
    public static final String PROCESSING = "PROCESSING";
    public static final String WAITING_FOR_APPROVAL = "WAITING_FOR_APPROVAL";
    public static final String COMPLETED = "COMPLETED";
    public static final String STOPPED = "STOPPED";
    public static final String FAILED = "FAILED";

    public static final Set<String> ALL = Set.of(RECEIVED, CLASSIFYING, NOT_PITCH, AWAITING_USER, PROCESSING,
            WAITING_FOR_APPROVAL, COMPLETED, STOPPED, FAILED);
    public static final Set<String> ACTIVE = Set.of(RECEIVED, CLASSIFYING, PROCESSING);
    public static final Set<String> NEEDS_USER = Set.of(AWAITING_USER, WAITING_FOR_APPROVAL, FAILED);

    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            RECEIVED, Set.of(CLASSIFYING, FAILED, STOPPED),
            CLASSIFYING, Set.of(CLASSIFYING, AWAITING_USER, NOT_PITCH, FAILED, STOPPED),
            NOT_PITCH, Set.of(PROCESSING, STOPPED),
            AWAITING_USER, Set.of(AWAITING_USER, PROCESSING, WAITING_FOR_APPROVAL, COMPLETED, STOPPED, FAILED),
            PROCESSING, Set.of(PROCESSING, AWAITING_USER, WAITING_FOR_APPROVAL, FAILED, STOPPED),
            WAITING_FOR_APPROVAL, Set.of(WAITING_FOR_APPROVAL, PROCESSING, COMPLETED, STOPPED, FAILED),
            FAILED, Set.of(CLASSIFYING, PROCESSING, WAITING_FOR_APPROVAL, STOPPED),
            COMPLETED, Set.of(),
            STOPPED, Set.of());

    private WorkflowStatus() { }

    public static boolean isFinal(String status) {
        return COMPLETED.equals(status) || STOPPED.equals(status);
    }

    public static boolean canTransition(String from, String to) {
        return TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public static void requireTransition(String from, String to) {
        if (!canTransition(from, to)) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "A workflow cannot move from " + from + " to " + to + ".");
        }
    }
}
