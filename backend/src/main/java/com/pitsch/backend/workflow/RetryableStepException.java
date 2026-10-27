package com.pitsch.backend.workflow;

/** A step failed for a transient reason (provider outage, timeout); the job queue retries it with backoff. */
public class RetryableStepException extends RuntimeException {

    private final String step;

    public RetryableStepException(String step, String message) {
        super(message);
        this.step = step;
    }

    public String step() {
        return step;
    }
}
