package com.pitsch.backend.workflow;

/** Controlled workflow statuses from the frontend/backend contract. */
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

    private WorkflowStatus() { }

    public static boolean isFinal(String status) {
        return COMPLETED.equals(status) || STOPPED.equals(status);
    }
}
