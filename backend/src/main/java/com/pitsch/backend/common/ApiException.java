package com.pitsch.backend.common;

import org.springframework.http.HttpStatus;

/**
 * Thrown by services/controllers; rendered by {@link GlobalExceptionHandler} as
 * {@code {"code","message","requestId","details","status"}}. Messages must be safe to show to end users.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final HttpStatus status;
    private final transient Object details;

    public ApiException(ErrorCode code, String message) {
        this(code, code.status(), message, null);
    }

    public ApiException(ErrorCode code, String message, Object details) {
        this(code, code.status(), message, details);
    }

    public ApiException(ErrorCode code, HttpStatus status, String message, Object details) {
        super(message);
        this.code = code;
        this.status = status;
        this.details = details;
    }

    /** Backwards-compatible constructor used by older call sites. */
    public ApiException(HttpStatus status, String message) {
        this(codeFor(status), status, message, null);
    }

    public ErrorCode getCode() { return code; }
    public HttpStatus getStatus() { return status; }
    public Object getDetails() { return details; }

    public static ApiException notFound(String what) {
        return new ApiException(ErrorCode.NOT_FOUND, what + " not found");
    }

    public static ApiException badRequest(String message) {
        return new ApiException(ErrorCode.BAD_REQUEST, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(ErrorCode.CONFLICT, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(ErrorCode.UNAUTHENTICATED, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(ErrorCode.FORBIDDEN, message);
    }

    private static ErrorCode codeFor(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> ErrorCode.BAD_REQUEST;
            case UNAUTHORIZED -> ErrorCode.UNAUTHENTICATED;
            case FORBIDDEN -> ErrorCode.FORBIDDEN;
            case NOT_FOUND -> ErrorCode.NOT_FOUND;
            case CONFLICT -> ErrorCode.CONFLICT;
            case TOO_MANY_REQUESTS -> ErrorCode.RATE_LIMITED;
            case BAD_GATEWAY -> ErrorCode.AI_SERVICE_ERROR;
            case SERVICE_UNAVAILABLE -> ErrorCode.SERVICE_UNAVAILABLE;
            default -> status.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.BAD_REQUEST;
        };
    }
}
