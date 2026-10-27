package com.pitsch.backend.common;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The single error format of the API. {@code status} duplicates the HTTP status for clients that only see the body.
 * {@code requestId} lets users quote a support reference; the server logs carry the technical details.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String code, String message, String requestId, Object details, int status) {
}
