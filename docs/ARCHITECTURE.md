# Architecture

Pitsch has three deployable services and two managed dependencies.

```
                         Browser (React SPA)
                               │  HTTPS, same site: /  and /api/*
                               ▼
              ┌──────────────────────────────────┐
              │ frontend  (nginx, static SPA)    │  CSP, security headers, /api proxy
              └───────────────┬──────────────────┘
                              │ /api/*
                              ▼
┌───────────────────────────────────────────────────────────────────────────────────────┐
│ backend  (Spring Boot 3.5, Java 21) — the only component with credentials             │
│                                                                                       │
│  RequestIdFilter → CorsFilter → SecurityHeaders → IdempotencyFilter                   │
│        → AuthInterceptor (default deny: session, verified email, workspace, RBAC)     │
│                                                                                       │
│  REST /api/v1/*  auth · me · workspace · members · pitches · emails · workflows       │
│                  drafts · approvals · calendar · tasks · notifications · integrations │
│                  dashboard · billing · audit · privacy · webhooks (gmail, stripe)     │
│                                                                                       │
│  WorkflowEngine ── state machine (WorkflowStatus) ── WorkflowSteps ── AgentRunner ──┐ │
│  ApprovalService ── ActionService ── external_operations ledger (idempotency)       │ │
│  JobRunner ◄── jobs table (PostgreSQL queue: classify, pipeline, approvals, Gmail,  │ │
│                 calendar sync, notifications, sheets, retention, recovery)          │ │
│  ProviderRegistry: EmailProvider / CalendarProvider / SpreadsheetProvider           │ │
│                    Google* (production)  |  Mock* (PITSCH_MODE=demo only)           │ │
│  StorageProvider: S3-compatible (production) | local disk (dev/demo)                │ │
│  TokenCipher (AES-256-GCM) · AuditService (append-only) · EntitlementService        │ │
└──────┬──────────────────────┬────────────────────┬────────────────────────────────────┼─┘
       │ JDBC                 │ S3 API             │ Google APIs (OAuth tokens)         │ HTTP + X-Internal-Token
       ▼                      ▼                    ▼                                    ▼
  PostgreSQL 16          Object storage      Gmail · Calendar · Sheets      ┌───────────────────────────────────┐
  (Flyway schema,        (pitch decks,       Pub/Sub push → /webhooks/gmail │ ai-service (FastAPI, Python 3.12) │
   job queue, audit)      attachments)                                      │ 8 agents, no external credentials │
                                                                            │ except the LLM key and search key │
                                                                            └───────┬──────────────┬────────────┘
                                                                                    ▼              ▼
                                                                         LLM (Gemini / OpenAI-  Tavily search
                                                                         compatible, fallback)
```

## Responsibilities

| Component | Owns | Never does |
|---|---|---|
| frontend | Rendering, forms, polling workflow status, short-lived access token in memory | Store tokens in localStorage, decide permissions (it only hides buttons), send a workspace id |
| backend | Identity, tenancy, RBAC, persistence, workflow state, approvals, every external side effect, quotas, audit | Trust model output as an instruction; trust client-supplied organization or role |
| ai-service | Stateless agent computation: classification, extraction, research, verification, analysis, drafting, slot ranking, action planning | Hold Google/DB/SMTP credentials, call Pitsch back, perform side effects |
| PostgreSQL | All state, including the job queue and the audit trail | — |
| Object storage | Attachment bytes (private bucket, presigned GETs) | Public access |

## Request lifecycle

1. `RequestIdFilter` assigns/propagates `X-Request-Id` (also sent to the AI service and written to every log line
   and error body).
2. `CorsFilter` applies the origin allowlist; `SecurityHeadersFilter` adds HSTS, CSP, frame/nosniff headers.
3. `IdempotencyFilter` replays a stored response for a repeated `Idempotency-Key`.
4. `AuthInterceptor` resolves the session from the bearer token, checks it is not revoked, enforces CSRF for
   cookie-based endpoints, email verification, workspace membership and `@RequiresPermission`.
5. Controllers receive an `AuthPrincipal(userId, orgId, role, sessionId)` — the only source of tenant scope.
6. Errors leave through `GlobalExceptionHandler` as `{code, message, requestId, details}`; stack traces are logged
   server-side only.

## A pitch, end to end

```
Gmail push / poll ─┐                                   manual import (multipart) ─┐
                   ▼                                                              ▼
            GMAIL_INGEST_MESSAGE job ─────────────► EmailIngestionService (validate, dedupe by external id,
                                                     store attachments, create Workflow RECEIVED, enqueue)
                                                                   │
                                                     CLASSIFY_EMAIL job → Email Classifier
                                                                   │
                         ┌─────────────────────────────────────────┼───────────────────────────┐
                    NOT_PITCH                               AWAITING_USER                follow-up linked to pitch
                (user may "handle anyway")         user: Handle pitch / Stop               (same flow)
                                                                   │
                                     RUN_PIPELINE job: Document → Research → Verification → Analysis
                                     (each step persisted as an agent_execution; resumable)
                                                                   │
                                     AWAITING_USER with the investment brief (no invest/pass verdict)
                         ┌─────────────────────────┬───────────────┴─────────────┐
                 PLAN_MEETING job           DRAFT_EMAIL job           WORKFLOW_EVENT_ACTIONS job
              Calendar Agent ranks slots   Email Response Agent       Action Agent → labels / pipeline
              backend checks free/busy     drafts a reply              fields (policy AUTO|APPROVAL|OFF)
                         │                         │                             │
                user picks slot             user edits & approves          approvals (if policy says so)
                         └────────────► approvals → EXECUTE_APPROVAL job ◄───────┘
                                       external_operations (idempotency key) → provider
                                                   │
                                        audit event + notification → COMPLETED
```

State transitions are validated by `WorkflowStatus.requireTransition`; every write uses optimistic locking
(`@Version`) so two workers or a user action racing a job cannot overwrite each other.

## Data model (main tables)

`organizations`, `memberships`, `invitations`, `users`, `sessions`, `refresh_tokens`, `user_tokens`
· `emails`, `email_attachments`, `stored_files` · `pitches` (deal stage, owner, confidence, risk, thread ids)
· `workflows` (status, step, outputs, version) · `agent_executions` (model, tokens, cost, latency, input hash,
retries, error category) · `email_drafts`, `calendar_events`, `tasks` · `approvals`, `external_operations`
· `jobs`, `inbound_events` (webhook/message dedupe), `idempotency_records` · `integration_connections`
(encrypted tokens, scopes, sync state), `oauth_states`, `pipeline_sheet_configs`, `pipeline_sheet_rows`
· `notifications`, `notification_preferences` · `audit_events` (append-only: a PostgreSQL trigger rejects every UPDATE;
the application only deletes rows through the audit retention purge) · `plans`, `subscriptions`, `usage_records`.

Every tenant table carries `organization_id` with an index leading on it; external ids (`gmail_message_id`,
`thread_id`, calendar event ids, sheet row ids, Stripe ids) have unique constraints scoped to the workspace.

## Modes

| `PITSCH_MODE` | Database | Google | Mail | Storage | Demo data |
|---|---|---|---|---|---|
| `production` (default) | PostgreSQL required | real providers; integrations unavailable until OAuth is configured | SMTP required | S3 required | never |
| `development` | H2 file DB or PostgreSQL | real providers if configured | logged | local disk | no |
| `demo` | as development | **mock** providers, labelled "demo" | logged | local disk | `demo@pitsch.app` workspace |
| `test` (tests only) | H2 in-memory or PostgreSQL (`TEST_DB_URL`) | not configured (workflow tests switch to demo) | logged | local | no |

See [ARCHITECTURAL_DECISIONS.md](ARCHITECTURAL_DECISIONS.md) for why each piece is built the way it is,
[AI_ARCHITECTURE.md](AI_ARCHITECTURE.md) for the agents and [SECURITY.md](SECURITY.md) for the threat model.
