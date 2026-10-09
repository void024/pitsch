package com.pitsch.backend.auth;

/** Fine-grained server-side permissions; roles map to sets of these (see {@link com.pitsch.backend.org.Role}). */
public enum Permission {
    PITCH_READ,
    PITCH_WRITE,
    PITCH_DELETE,
    EMAIL_IMPORT,
    /** Run AI workflow steps (classification follow-ups, research, drafting, slot finding). */
    WORKFLOW_RUN,
    /** Approve consequential external actions: send email, create meeting, sync pipeline. */
    ACTION_APPROVE,
    CALENDAR_READ,
    CALENDAR_WRITE,
    TASK_READ,
    TASK_WRITE,
    /** Connect one's own Google account (Gmail / Calendar). */
    INTEGRATION_CONNECT,
    /** Workspace-level integration settings (pipeline sheet, disconnecting others). */
    INTEGRATION_MANAGE,
    MEMBER_READ,
    MEMBER_MANAGE,
    ORG_SETTINGS,
    AUDIT_READ,
    DATA_EXPORT,
    ORG_DELETE,
    USAGE_READ,
    BILLING_MANAGE
}
