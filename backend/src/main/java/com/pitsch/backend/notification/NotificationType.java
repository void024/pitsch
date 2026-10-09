package com.pitsch.backend.notification;

import java.util.EnumSet;
import java.util.Set;

public enum NotificationType {
    NEW_PITCH,
    FOLLOW_UP_DETECTED,
    POSSIBLE_PITCH,
    ANALYSIS_READY,
    APPROVAL_REQUIRED,
    EMAIL_DRAFT_READY,
    MEETING_SLOTS_READY,
    MEETING_SCHEDULED,
    MEETING_CHANGED,
    EMAIL_SENT,
    WORKFLOW_COMPLETED,
    WORKFLOW_FAILED,
    INTEGRATION_ERROR,
    QUOTA_REACHED;

    /** Types emailed by default (the rest are in-app only unless the user opts in). */
    public static final Set<NotificationType> EMAIL_BY_DEFAULT = EnumSet.of(ANALYSIS_READY, APPROVAL_REQUIRED,
            WORKFLOW_FAILED, INTEGRATION_ERROR, MEETING_CHANGED, QUOTA_REACHED);
}
