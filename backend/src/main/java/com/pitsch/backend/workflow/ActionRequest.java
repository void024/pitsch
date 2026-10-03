package com.pitsch.backend.workflow;

import java.util.List;

/**
 * Body of POST /api/workflows/{id}/action. Only `action` is required (exact enum value, e.g. "PLAN_MEETING").
 * Optional extras: for PLAN_EMAIL_RESPONSE — purpose, instructions, questions; for PLAN_MEETING — durationMinutes.
 */
public record ActionRequest(String action, String purpose, String instructions, List<String> questions,
                            Integer durationMinutes) {
}
