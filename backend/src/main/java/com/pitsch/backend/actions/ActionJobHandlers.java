package com.pitsch.backend.actions;

import com.fasterxml.jackson.databind.JsonNode;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.integration.IntegrationException;
import com.pitsch.backend.jobs.Job;
import com.pitsch.backend.jobs.JobHandler;
import com.pitsch.backend.jobs.JobType;
import org.springframework.stereotype.Component;

public final class ActionJobHandlers {

    private ActionJobHandlers() { }

    /** Executes an approved action. Permanent provider errors fail fast; transient ones are retried with backoff. */
    @Component
    public static class ExecuteApproval implements JobHandler {
        private final ActionService actions;

        public ExecuteApproval(ActionService actions) {
            this.actions = actions;
        }

        @Override
        public JobType type() {
            return JobType.EXECUTE_APPROVAL;
        }

        @Override
        public void handle(Job job, JsonNode payload) {
            actions.executeApproval(payload.path("approvalId").asLong());
        }

        @Override
        public boolean isRetryable(Exception e) {
            if (e instanceof IntegrationException ie) {
                return ie.isRetryable();
            }
            return !(e instanceof ApiException) && !(e instanceof IllegalStateException);
        }

        @Override
        public void onGiveUp(Job job, JsonNode payload, Exception lastError) {
            String message = lastError instanceof ApiException ? lastError.getMessage() : "Unexpected error while executing the action.";
            actions.approvalFailed(payload.path("approvalId").asLong(), message);
        }
    }

    /** Post-event actions (labels, pipeline sheet). Failures never affect the workflow itself. */
    @Component
    public static class WorkflowEventActions implements JobHandler {
        private final ActionPlanner planner;

        public WorkflowEventActions(ActionPlanner planner) {
            this.planner = planner;
        }

        @Override
        public JobType type() {
            return JobType.WORKFLOW_EVENT_ACTIONS;
        }

        @Override
        public void handle(Job job, JsonNode payload) {
            planner.handleEvent(payload.path("workflowId").asLong(), payload.path("event").asText());
        }
    }
}
