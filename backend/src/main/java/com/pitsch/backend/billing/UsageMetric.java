package com.pitsch.backend.billing;

/** Metered quantities. Keys of plans.limits_json. */
public enum UsageMetric {
    PITCHES_PROCESSED,
    AI_WORKFLOWS,
    AI_TOKENS,
    RESEARCH_QUERIES,
    DOCUMENT_PAGES,
    STORAGE_MB,
    MEMBERS
}
