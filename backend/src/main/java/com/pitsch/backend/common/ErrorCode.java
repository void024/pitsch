package com.pitsch.backend.common;

import org.springframework.http.HttpStatus;

/** Stable, machine-readable error codes returned in every error body ({@code code}). */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    BAD_REQUEST(HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED),
    SESSION_EXPIRED(HttpStatus.UNAUTHORIZED),
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN),
    ACCOUNT_LOCKED(HttpStatus.LOCKED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    CONFLICT(HttpStatus.CONFLICT),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    FILE_REJECTED(HttpStatus.UNPROCESSABLE_ENTITY),
    QUOTA_EXCEEDED(HttpStatus.PAYMENT_REQUIRED),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    INTEGRATION_NOT_CONNECTED(HttpStatus.CONFLICT),
    INTEGRATION_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE),
    INTEGRATION_ERROR(HttpStatus.BAD_GATEWAY),
    AI_SERVICE_ERROR(HttpStatus.BAD_GATEWAY),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
