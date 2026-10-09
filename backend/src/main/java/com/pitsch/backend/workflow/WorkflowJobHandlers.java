package com.pitsch.backend.workflow;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.jobs.Job;
import com.pitsch.backend.jobs.JobHandler;
import com.pitsch.backend.jobs.JobType;
import org.springframework.stereotype.Component;

/** Job handlers for the background workflow steps. Retryable agent failures are retried by the job queue. */
public final class WorkflowJobHandlers {

    private WorkflowJobHandlers() { }

    private abstract static class StepHandler implements JobHandler {
        protected final WorkflowSteps steps;

        StepHandler(WorkflowSteps steps) {
            this.steps = steps;
        }

        @Override
        public boolean isRetryable(Exception e) {
            if (e instanceof RetryableStepException || e instanceof org.springframework.orm.ObjectOptimisticLockingFailureException) {
                return true;
            }
            if (e instanceof WorkflowStore.StoppedException) {
                return false;
            }
            return JobHandler.super.isRetryable(e);
        }

        @Override
        public void handle(Job job, JsonNode payload) {
            try {
                run(payload.path("workflowId").asLong(), payload);
            } catch (WorkflowStore.StoppedException stopped) {
                // the user stopped the workflow while this step ran: nothing left to do
            }
        }

        abstract void run(Long workflowId, JsonNode payload);
    }

    @Component
    public static class ClassifyEmail extends StepHandler {
        public ClassifyEmail(WorkflowSteps steps) {
            super(steps);
        }

        @Override
        public JobType type() {
            return JobType.CLASSIFY_EMAIL;
        }

        @Override
        void run(Long workflowId, JsonNode payload) {
            steps.classify(workflowId);
        }

        @Override
        public void onGiveUp(Job job, JsonNode payload, Exception lastError) {
            steps.fail(payload.path("workflowId").asLong(), "CLASSIFYING", message(lastError, "Email classification failed"));
        }
    }

    @Component
    public static class RunPipeline extends StepHandler {
        public RunPipeline(WorkflowSteps steps) {
            super(steps);
        }

        @Override
        public JobType type() {
            return JobType.RUN_PIPELINE;
        }

        @Override
        void run(Long workflowId, JsonNode payload) {
            steps.runPipeline(workflowId);
        }

        @Override
        public void onGiveUp(Job job, JsonNode payload, Exception lastError) {
            String step = lastError instanceof RetryableStepException r ? r.step() : "PROCESSING";
            steps.fail(payload.path("workflowId").asLong(), step, message(lastError, "Research pipeline failed"));
        }
    }

    @Component
    public static class PlanMeeting extends StepHandler {
        public PlanMeeting(WorkflowSteps steps) {
            super(steps);
        }

        @Override
        public JobType type() {
            return JobType.PLAN_MEETING;
        }

        @Override
        void run(Long workflowId, JsonNode payload) {
            Integer duration = payload.hasNonNull("durationMinutes") ? payload.path("durationMinutes").asInt() : null;
            Long planner = payload.hasNonNull("plannerUserId") ? payload.path("plannerUserId").asLong() : null;
            steps.planMeeting(workflowId, duration, planner);
        }

        @Override
        public void onGiveUp(Job job, JsonNode payload, Exception lastError) {
            steps.backToReview(payload.path("workflowId").asLong(), message(lastError, "Could not find meeting slots"));
        }
    }

    @Component
    public static class DraftEmail extends StepHandler {
        public DraftEmail(WorkflowSteps steps) {
            super(steps);
        }

        @Override
        public JobType type() {
            return JobType.DRAFT_EMAIL;
        }

        @Override
        void run(Long workflowId, JsonNode payload) {
            List<String> questions = new ArrayList<>();
            payload.path("questions").forEach(q -> questions.add(q.asText()));
            Long author = payload.hasNonNull("authorUserId") ? payload.path("authorUserId").asLong() : null;
            steps.planEmail(workflowId, payload.path("purpose").asText("ACKNOWLEDGE"), Json.text(payload, "instructions"),
                    questions, author);
        }

        @Override
        public void onGiveUp(Job job, JsonNode payload, Exception lastError) {
            steps.backToReview(payload.path("workflowId").asLong(), message(lastError, "Could not draft the email"));
        }
    }

    static String message(Exception e, String fallback) {
        if (e instanceof RetryableStepException || e instanceof com.pitsch.backend.common.ApiException) {
            return e.getMessage();
        }
        return fallback + " (internal error).";
    }
}
