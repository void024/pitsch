# API

Base path: **`/api/v1`**. The machine-readable contract is OpenAPI 3 at `/api/v1/openapi` (Swagger UI at `/api/docs`),
enabled by default in development/demo and off in production (`API_DOCS_ENABLED=true` to expose it — put it behind
your SSO/VPN if you do). The pre-upgrade unversioned routes (`/api/pitches`, `/api/emails/process`, …) still work for
backward compatibility and are scoped to the caller's workspace like everything else; new clients should use `/api/v1`.

## Conventions

**Authentication.** `POST /auth/login` (or `/auth/signup`) returns `{accessToken, tokenType, expiresIn, user, me}`
and sets the httpOnly refresh cookie. Send `Authorization: Bearer <accessToken>` on every call. When a call returns
`401` with code `SESSION_EXPIRED`/`UNAUTHENTICATED`, call `POST /auth/refresh` (cookie + `X-Requested-With`) once
and retry. Endpoints that read the cookie (`/auth/refresh`, `/auth/logout`) require `X-Requested-With: pitsch-web`.

**Workspace.** Never send an organization id. The workspace is the session's current workspace; change it with
`POST /auth/switch-workspace {organizationId}` (membership is checked). Ids from another workspace return `404`.

**Errors.** Every error has the same body:

```json
{ "code": "VALIDATION_FAILED", "message": "Check the highlighted fields.", "requestId": "4f1c…",
  "details": [ { "field": "founderEmail", "message": "must be a valid email" } ] }
```

| HTTP | `code` |
|---|---|
| 400 | `VALIDATION_FAILED`, `BAD_REQUEST` |
| 401 | `UNAUTHENTICATED`, `SESSION_EXPIRED`, `INVALID_CREDENTIALS` |
| 402 | `QUOTA_EXCEEDED` |
| 403 | `FORBIDDEN`, `EMAIL_NOT_VERIFIED` |
| 404 | `NOT_FOUND` |
| 409 | `CONFLICT` (stale `version`, busy resource), `INVALID_STATE_TRANSITION`, `INTEGRATION_NOT_CONNECTED` |
| 413 / 415 | `PAYLOAD_TOO_LARGE` / `UNSUPPORTED_MEDIA_TYPE` |
| 422 | `FILE_REJECTED`, `IDEMPOTENCY_KEY_REUSED` |
| 423 | `ACCOUNT_LOCKED` |
| 429 | `RATE_LIMITED` (with `Retry-After`) |
| 502 | `INTEGRATION_ERROR`, `AI_SERVICE_ERROR` |
| 503 | `INTEGRATION_NOT_CONFIGURED`, `SERVICE_UNAVAILABLE` |
| 500 | `INTERNAL_ERROR` |

Every response carries `X-Request-Id` (send your own to correlate). Quote it when reporting a problem.

**Pagination.** List endpoints take `page` (0-based), `size` (1–100, default 25) and `sort=field,asc|desc` from an
allowlist, and return `{items, page, size, totalItems, totalPages}`. A `size` over 100 is a `400`.

**Idempotency.** For `POST` requests that must not run twice (send email, create pitch, import), send
`Idempotency-Key: <8–100 chars>`. A repeat with the same key and body returns the original response with
`Idempotent-Replayed: true`; a different body with the same key is `422 IDEMPOTENCY_KEY_REUSED`; a repeat while the first is still running is
`409 CONFLICT`. Keys are kept
24 h per user.

**Concurrency.** Pitches carry `version`; send it on `PATCH` to get `409 CONFLICT` instead of overwriting a
teammate's change.

**Asynchronous work.** Imports and workflow actions return `202 Accepted` with the workflow; poll
`GET /workflows/{id}` (the UI polls every 2.5 s while `status` is `RECEIVED`, `CLASSIFYING` or `PROCESSING`).

## Endpoints

Permission names refer to [SECURITY.md](SECURITY.md#authorization-and-tenancy). "session" = any signed-in user.

### Auth and account

| Method & path | Access | Notes |
|---|---|---|
| `POST /auth/signup` | public | `{name, email, password, workspaceName?, timezone?, inviteToken?}` |
| `POST /auth/login` | public | `{email, password}`; rate limited; lockout after repeated failures |
| `POST /auth/refresh` | cookie | rotates the refresh token |
| `POST /auth/logout` | cookie | revokes the session |
| `POST /auth/verify-email` · `/auth/resend-verification` | public · session | |
| `POST /auth/forgot-password` · `/auth/reset-password` | public | always the same response for unknown emails |
| `POST /auth/change-password` | session | revokes other sessions |
| `GET /auth/sessions` · `DELETE /auth/sessions/{id}` | session | device list and revocation |
| `POST /auth/switch-workspace` | session | `{organizationId}` |
| `GET /auth/google/start` | public | optional Google sign-in |
| `GET /me` | session | user, workspace, role, **effective permissions**, memberships, feature flags |
| `PUT /users/me` · `GET/PUT /users/me/settings` | session | profile; personal preferences |
| `GET /me/export` · `POST /me/delete` | session | account export (JSON); deletion `{password, confirm: "DELETE"}` |

### Workspace

| Method & path | Access |
|---|---|
| `POST /workspaces` | session (creates a workspace, caller becomes OWNER) |
| `GET /workspace` · `PATCH /workspace` | session · `ORG_SETTINGS` (timezone, working hours/days, meeting length, retention, label/sheet policies) |
| `POST /workspace/onboarding/complete` | `ORG_SETTINGS` |
| `GET /workspace/members` · `PATCH/DELETE /workspace/members/{membershipId}` | `MEMBER_READ` · `MEMBER_MANAGE` (members may remove themselves) |
| `GET/POST /workspace/invitations` · `DELETE /workspace/invitations/{id}` | `MEMBER_MANAGE` |
| `GET /invitations/{token}` · `POST /invitations/{token}/accept` | public · session |
| `GET /workspace/export` · `POST /workspace/delete` | `DATA_EXPORT` (ZIP) · `ORG_DELETE` `{password, confirmName}` |
| `GET /audit-events` | `AUDIT_READ` (filters: `action`, `actorUserId`, `resourceType`, `from`, `to`; paginated) |
| `GET /activity` | `PITCH_READ` (human-readable activity feed) |

### Deal flow

| Method & path | Access | Notes |
|---|---|---|
| `GET /dashboard` | `PITCH_READ` | counts, pipeline by stage, attention items, meetings, activity, AI usage, quotas |
| `GET /pitches` | `PITCH_READ` | filters `q`, `dealStage` (csv), `status`, `sector`, `ownerUserId`, `riskLevel`, `minConfidence`, `hasFollowUp`, `source`, `createdFrom`, `createdTo`; sort `updatedAt, createdAt, lastActivityAt, companyName, aiConfidence, dealStage` |
| `GET /pitches/stages` | `PITCH_READ` | counts per deal stage |
| `POST /pitches` · `PATCH /pitches/{id}` · `DELETE /pitches/{id}` | `PITCH_WRITE` · `PITCH_WRITE` · `PITCH_DELETE` | delete refused (409) while a workflow is running |
| `GET /pitches/{id}` · `GET /pitches/{id}/timeline` | `PITCH_READ` | detail includes workflows and the latest brief |
| `POST /emails/import` | `EMAIL_IMPORT` | multipart: `sender, senderName?, subject, body, threadId?, messageId?, inReplyTo?, receivedAt?, files[]`; `202` new / `200` duplicate |
| `POST /emails` | `EMAIL_IMPORT` | JSON variant (attachments base64) |
| `GET /emails` · `GET /emails/{id}` | `PITCH_READ` | |
| `GET /emails/{emailId}/attachments/{attachmentId}/download` | `PITCH_READ` | short-lived presigned URL |
| `DELETE /emails/{id}` | `PITCH_DELETE` | erases the email, attachments and its workflows (409 while agents run) |
| `GET /workflows` | `PITCH_READ` | filters `status`, `type`, `needsReview`, `needsAction`, `pitchId`, `q`; paginated, sortable |
| `GET /workflows/{id}` | `PITCH_READ` | status, step, agents (live states), brief, slots, meeting, draft, review reasons |
| `POST /workflows/{id}/actions` | `WORKFLOW_RUN` | `{action: COMPLETE_WORKFLOW \| PLAN_MEETING \| PLAN_EMAIL_RESPONSE \| RETRY \| STOP}`; only `availableActions` are accepted |
| `GET /workflows/{id}/executions` · `/approvals` · `/draft` · `/brief.md` | `PITCH_READ` | agent log (no prompts), approvals, current draft, brief as Markdown |
| `PUT /drafts/{id}` · `POST /drafts/{id}/cancel` | `WORKFLOW_RUN` | edit / discard |
| `POST /drafts/{id}/send` | `ACTION_APPROVE` | approval + send; use `Idempotency-Key` |
| `GET /calendar/availability` | `PITCH_READ` | free/busy-checked slots |
| `POST /calendar/meetings` · `POST /workflows/{id}/meeting/cancel` | `ACTION_APPROVE` | approve creating / cancelling the meeting |
| `GET /approvals` · `POST /approvals/{id}/approve` · `/reject` | `PITCH_READ` · `ACTION_APPROVE` | `{reason}` on reject |
| `GET/POST /events` · `PUT/DELETE /events/{id}` | `CALENDAR_READ` · `CALENDAR_WRITE` | |
| `GET/POST /tasks` · `PUT/DELETE /tasks/{id}` · `PATCH /tasks/{id}/status` | `TASK_READ` · `TASK_WRITE` | |
| `GET /notifications` · `/unread-count` · `PATCH /notifications/{id}/read` · `/read-all` | session | |
| `GET/PUT /notifications/preferences` · `POST /notifications/test` | session | per type: in-app / email |

### Workflow states

`RECEIVED → CLASSIFYING → AWAITING_USER | NOT_PITCH → PROCESSING → AWAITING_USER | WAITING_FOR_APPROVAL →
COMPLETED`; `FAILED` (retryable) and `STOPPED` from any non-final state. Illegal transitions return
`409 INVALID_STATE_TRANSITION`. `availableActions` on each workflow lists what the caller may do next.

### Integrations and billing

| Method & path | Access |
|---|---|
| `GET /integrations` | session — status, account, scopes, last sync, errors per integration |
| `POST /integrations/connect` `{integrations: [GMAIL\|CALENDAR\|SHEETS], returnPath}` | `INTEGRATION_CONNECT` → `{authorizationUrl}` |
| `GET /integrations/google/callback` | public (OAuth redirect; state-verified) |
| `DELETE /integrations/{GMAIL\|CALENDAR\|SHEETS}` | `INTEGRATION_CONNECT` (Sheets: `INTEGRATION_MANAGE`) |
| `POST /integrations/gmail/sync` | `EMAIL_IMPORT` |
| `GET /integrations/sheets/config` · `/fields` · `/preview` | `PITCH_READ` · session · `INTEGRATION_MANAGE` |
| `PUT/DELETE /integrations/sheets/config` · `POST /integrations/sheets/sync-all` | `INTEGRATION_MANAGE` · `ACTION_APPROVE` |
| `GET /billing` · `GET /billing/plans` | `USAGE_READ` · session |
| `POST /billing/checkout` `{planCode}` · `POST /billing/portal` | `BILLING_MANAGE` → `{url}` |
| `POST /webhooks/stripe` · `POST /webhooks/gmail` | public, signature / OIDC verified |

### Health

| Path | Use |
|---|---|
| `GET /health/live` | liveness (process up) — outside `/api`, for the platform |
| `GET /health/ready` | readiness (accepting traffic + database reachable) |
| `GET /api/v1/health` | public status incl. AI service reachability |
| `:8081/actuator/prometheus` | metrics on the private management port |

The AI service is internal and documented in [`ai-service/docs/integration.md`](../ai-service/docs/integration.md).
