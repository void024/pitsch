package com.pitsch.backend.pitch;

/** Human-owned pipeline stage. Pitsch never moves a deal to INVESTED or PASSED on its own. */
public enum DealStage {
    NEW, SCREENING, DILIGENCE, MEETING, DECISION, INVESTED, PASSED, ARCHIVED
}
