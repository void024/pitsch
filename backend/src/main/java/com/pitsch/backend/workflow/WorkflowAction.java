package com.pitsch.backend.workflow;

/**
 * User actions (exact values from the contract, plus RETRY for failed workflows).
 *
 * COMPLETE_WORKFLOW means "handle this pitch": it runs the research pipeline (Document → Research →
 * Verification → Analysis) if no brief exists yet; once a brief exists — or the founder closed the
 * conversation — it completes (closes) the workflow.
 */
public enum WorkflowAction {
    COMPLETE_WORKFLOW,
    STOP,
    PLAN_MEETING,
    PLAN_EMAIL_RESPONSE,
    RETRY
}
