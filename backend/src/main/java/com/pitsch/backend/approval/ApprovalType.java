package com.pitsch.backend.approval;

/** Consequential external actions. SEND_EMAIL and CREATE/CANCEL_MEETING always need a human approval. */
public enum ApprovalType {
    SEND_EMAIL,
    CREATE_MEETING,
    CANCEL_MEETING,
    SYNC_PIPELINE,
    APPLY_LABELS
}
