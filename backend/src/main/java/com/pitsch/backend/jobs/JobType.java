package com.pitsch.backend.jobs;

/** Background job types. Each has exactly one {@link JobHandler}. */
public enum JobType {
    CLASSIFY_EMAIL(5),
    RUN_PIPELINE(5),
    PLAN_MEETING(4),
    DRAFT_EMAIL(4),
    WORKFLOW_EVENT_ACTIONS(6),
    EXECUTE_APPROVAL(5),
    GMAIL_SYNC(4),
    GMAIL_INGEST_MESSAGE(5),
    GMAIL_WATCH(3),
    CALENDAR_SYNC(3),
    NOTIFICATION_EMAIL(5),
    PIPELINE_SHEET_SYNC(5),
    WORKSPACE_DELETE(8),
    RETENTION_PURGE(3),
    WORKFLOW_RECOVERY(3);

    private final int maxAttempts;

    JobType(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public int maxAttempts() {
        return maxAttempts;
    }
}
