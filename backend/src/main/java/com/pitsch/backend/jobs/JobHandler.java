package com.pitsch.backend.jobs;

import com.fasterxml.jackson.databind.JsonNode;

/** Executes one job type. Throwing marks the attempt failed; see {@link #isRetryable(Exception)}. */
public interface JobHandler {

    JobType type();

    void handle(Job job, JsonNode payload) throws Exception;

    /** Whether a failed attempt should be retried (default: yes, except for clearly permanent errors). */
    default boolean isRetryable(Exception e) {
        if (e instanceof com.pitsch.backend.integration.IntegrationException ie) {
            return ie.isRetryable();
        }
        return !(e instanceof com.pitsch.backend.common.ApiException) && !(e instanceof IllegalArgumentException);
    }

    /** Called once when the job gives up (dead-letter). Must not throw. */
    default void onGiveUp(Job job, JsonNode payload, Exception lastError) {
    }
}
