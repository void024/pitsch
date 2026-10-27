package com.pitsch.backend.common;

import java.util.List;
import java.util.Map;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Every error leaves the API as {@link com.pitsch.backend.common.ErrorResponse}. Messages are written for end users;
 * framework/stack details are logged with the request ID and never returned.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record FieldProblem(String field, String message) { }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<com.pitsch.backend.common.ErrorResponse> handleApi(ApiException ex) {
        if (ex.getStatus().is5xxServerError()) {
            log.warn("API error {}: {}", ex.getCode(), ex.getMessage());
        }
        return body(ex.getStatus(), ex.getCode(), ex.getMessage(), ex.getDetails());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<com.pitsch.backend.common.ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<FieldProblem> problems = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new FieldProblem(e.getField(), e.getDefaultMessage()))
                .toList();
        String message = problems.isEmpty() ? "Invalid request"
                : problems.get(0).field() + " " + problems.get(0).message();
        return body(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, message, problems);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<com.pitsch.backend.common.ErrorResponse> handleConstraint(ConstraintViolationException ex) {
        List<FieldProblem> problems = ex.getConstraintViolations().stream()
                .map(v -> new FieldProblem(String.valueOf(v.getPropertyPath()), v.getMessage()))
                .toList();
        return body(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Invalid request", problems);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<com.pitsch.backend.common.ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return body(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "Request body is missing or malformed", null);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<com.pitsch.backend.common.ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        return body(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "Missing parameter: " + ex.getParameterName(), null);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<com.pitsch.backend.common.ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
        return body(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "Missing header: " + ex.getHeaderName(), null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<com.pitsch.backend.common.ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return body(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "Invalid value for " + ex.getName(), null);
    }

    @ExceptionHandler({MaxUploadSizeExceededException.class})
    public ResponseEntity<com.pitsch.backend.common.ErrorResponse> handleTooLarge(Exception ex) {
        return body(HttpStatus.PAYLOAD_TOO_LARGE, ErrorCode.PAYLOAD_TOO_LARGE, "The upload is too large", null);
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<com.pitsch.backend.common.ErrorResponse> handleMultipart(MultipartException ex) {
        return body(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, "The upload could not be read", null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<com.pitsch.backend.common.ErrorResponse> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        return body(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Unsupported content type", null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<com.pitsch.backend.common.ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException ex) {
        return body(HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.BAD_REQUEST, "Method not allowed", null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<com.pitsch.backend.common.ErrorResponse> handleNoResource(NoResourceFoundException ex) {
        return body(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "Not found", null);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<com.pitsch.backend.common.ErrorResponse> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        return body(HttpStatus.CONFLICT, ErrorCode.CONFLICT,
                "This record was changed by someone else. Reload and try again.", null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<com.pitsch.backend.common.ErrorResponse> handleIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getClass().getSimpleName());
        return body(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "The request conflicts with existing data.", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<com.pitsch.backend.common.ErrorResponse> handleOther(Exception ex) {
        if (ex instanceof ErrorResponse er) {
            HttpStatus status = HttpStatus.valueOf(er.getStatusCode().value());
            if (!status.is5xxServerError()) {
                return body(status, status == HttpStatus.NOT_FOUND ? ErrorCode.NOT_FOUND : ErrorCode.BAD_REQUEST,
                        status.getReasonPhrase(), null);
            }
        }
        log.error("Unhandled error", ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "Something went wrong on our side. Quote the request ID if you contact support.", null);
    }

    public static ResponseEntity<com.pitsch.backend.common.ErrorResponse> body(HttpStatus status, ErrorCode code,
                                                                               String message, Object details) {
        return ResponseEntity.status(status)
                .body(new com.pitsch.backend.common.ErrorResponse(code.name(), message, RequestContext.requestId(),
                        details, status.value()));
    }

    /** Used by filters/interceptors that write errors before reaching a controller. */
    public static Map<String, Object> errorMap(ErrorCode code, String message) {
        java.util.LinkedHashMap<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("code", code.name());
        map.put("message", message);
        map.put("requestId", RequestContext.requestId());
        map.put("status", code.status().value());
        return map;
    }
}
