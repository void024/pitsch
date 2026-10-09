# Architectural Decisions

Each record covers the context, what was decided, why, and what it costs. They were written during the
production upgrade (October 2026) and are kept with the code: change the code, update the record.

---

## ADR-001 — PostgreSQL + Flyway is the system of record

**Context.** Before the upgrade the backend defaulted to an H2 file database with `ddl-auto=update`. Workflow
state, approvals, the job queue and the audit trail need durable, transactional, relational storage.

**Decision.** PostgreSQL 16 is the production database. Flyway owns the schema
(`backend/src/main/resources/db/migration/common` + `{vendor}`), Hibernate only **validates** it
(`ddl-auto=validate`). Existing databases are baselined at V1 (`baseline-on-migrate`), and V2 migrates the old
single-user data into per-user workspaces. H2 (PostgreSQL mode) remains only for development/demo and the default
test run. `ProductionConfigValidator` refuses to start production on a non-PostgreSQL URL.

**Consequences.** Schema changes need a migration; vendor-specific SQL (full-text search, audit immutability
trigger) lives in `postgresql/V3`, with an H2 stand-in in `h2/V3`.

## ADR-002 — Organization = workspace; tenancy derived from the session

**Context.** All data was scoped by `user_id`. Teams need shared deal flow.

**Decision.** One concept, `organizations` (shown as "workspace" in the UI), with `memberships(role)`. Every
tenant-owned table has `organization_id NOT NULL` and every repository method takes the organization from
`AuthPrincipal.orgId()`, which comes from the server-side session — never from a request parameter or header.
Switching workspace (`POST /api/v1/auth/switch-workspace`) re-issues the session after checking membership.
Lookups by id are always `findByIdAndOrganizationId`, so a foreign id is a 404 (no existence oracle).

**Consequences.** No separate "workspace inside organization" level; it can be added later without changing
tenant isolation. Tenant isolation is tested in `TenantIsolationTests`.

## ADR-003 — Custom JWT + DB sessions behind a default-deny interceptor (not Spring Security)

**Context.** The existing code had a small custom JWT interceptor. Spring Security would be a large rewrite of
every controller test and of the error format.

**Decision.** Keep the interceptor, harden it: HS256 access tokens (15 min) carrying a session id; DB-backed
sessions that can be revoked; rotating opaque refresh tokens (SHA-256 hashed at rest) in an httpOnly,
`Path=/api/v1/auth` cookie; reuse detection revokes the whole session family (a short configurable grace window
`REFRESH_REUSE_GRACE_SECONDS` absorbs parallel-tab races). The interceptor is **default-deny**: an endpoint is
public only with `@PublicEndpoint`; everything else needs a valid session, a verified email (unless
`@AllowUnverified`), a workspace (unless `@AllowWithoutWorkspace`) and its `@RequiresPermission`.
`BackendApplicationTests` pins the exact list of public routes.

**Consequences.** We own the security code, so it has dedicated unit tests (`SecurityUnitTests`). Moving to Spring
Security later is possible behind the same annotations.

## ADR-004 — PostgreSQL job queue instead of Redis

**Context.** The brief suggests Redis + a queue for long AI work. The deployment already requires PostgreSQL;
adding Redis adds an operational dependency and a second source of truth for workflow state.

**Decision.** A durable job table (`jobs`) polled by every backend instance; a job is claimed with an atomic
conditional `UPDATE … WHERE status = 'QUEUED'` (only one worker can win), with per-type max
attempts, exponential backoff, lock timeouts (crashed workers' jobs are reclaimed), dedupe keys (one live job per
workflow step) and an inline mode for tests. API calls enqueue and return `202`; the UI polls workflow status.
Job enqueue happens in the same transaction as the state change, so a job can never be lost or run for a state
that was rolled back.

**Consequences.** Throughput is bounded by PostgreSQL (thousands of jobs/minute — far above the expected load of
AI workflows that take tens of seconds each). If that changes, `JobQueue` is the single seam to replace.
Metrics: `pitsch.jobs.queue.depth`, `pitsch.jobs.finished`.

## ADR-005 — Approvals + external-operation ledger for every consequential action

**Decision.** AI output never triggers an external side effect directly. The backend turns agent output into an
`approvals` row (`SEND_EMAIL`, `CREATE_MEETING`, `CANCEL_MEETING`, `SYNC_PIPELINE`, `APPLY_LABELS`); a member with
`ACTION_APPROVE` approves; an `EXECUTE_APPROVAL` job performs it through the provider. Each execution first writes
an `external_operations` row keyed by a deterministic idempotency key (unique index); retries re-use it and
providers receive the key (Gmail: stored message id; Calendar: event id derived from the key; Sheets: row id
tracking), so a retry after a timeout cannot send twice. Sending email, creating and cancelling meetings always
require approval; Gmail labels and Sheets sync have a workspace policy `AUTO | APPROVAL | OFF`
(low-risk, internal-only effects).

## ADR-006 — Verification vocabulary: keep the agent's labels, map to product labels

**Context.** The Verification Agent already returned `VERIFIED / PARTIALLY_VERIFIED / UNVERIFIED / CONTRADICTED /
NOT_FOUND`. The product needs `SUPPORTED / PARTIALLY_SUPPORTED / UNSUPPORTED / CONTRADICTED / NOT_FOUND`.

**Decision.** Agent outputs gain an `assessment` field computed by a Pydantic validator from the original status;
existing prompts, tests and stored briefs keep working; the UI shows `assessment` (falling back to the mapping for
old briefs). "Independently verified" stays a separate boolean — company-controlled sources can only support a
claim partially.

## ADR-007 — Per-instance in-memory rate limiting

**Decision.** Fixed-window per-minute limits per IP / user for auth, API and AI-triggering endpoints, held in
memory. The cross-instance controls are database-backed: account lockout and monthly entitlements/quotas.
**Consequence.** With N instances the burst limit is effectively N×. Put an edge rate limit (Cloudflare, ALB WAF)
in front for internet-scale abuse; a Redis-backed `RateLimiter` is a drop-in replacement if needed.

## ADR-008 — Refresh token only in an httpOnly cookie; access token only in memory

The SPA never stores tokens in `localStorage`. A reload restores the session by calling `/auth/refresh` with the
cookie. Cookie-authenticated endpoints require the `X-Requested-With` header and an allowlisted `Origin`
(CSRF guard). Recommended deployment is same-site (SPA serves `/api` through a proxy, `SameSite=Lax`);
split-origin deployments need `COOKIE_SAME_SITE=None` + `COOKIE_SECURE=true` + exact CORS origins.

## ADR-009 — Gmail scope `gmail.modify`; incremental, per-integration grants

Pitsch needs to read messages, apply labels and send replies in the same thread. `gmail.modify` covers this with
one scope (it is a Google *restricted* scope — production use requires Google's OAuth verification and security
assessment, see docs/INTEGRATIONS.md). Calendar uses `calendar.events`, `calendar.freebusy` and
`calendar.calendarlist.readonly` (no full `calendar` scope); Sheets uses `spreadsheets`. Each integration is granted
separately with `include_granted_scopes`; disconnecting one removes only its scopes from Pitsch's record, and
disconnecting the last one revokes the Google grant. OAuth uses Authorization Code + PKCE with a single-use,
encrypted, 10-minute `oauth_states` row bound to the user and workspace.

## ADR-010 — The Action Agent recommends, the backend executes

The AI service holds no Google, database or email credentials. The Action Agent returns a structured plan
(labels to apply, pipeline fields); the backend validates every item against an allowlist (label prefix, known
pipeline fields), applies workspace policy and creates approvals. Recipients of drafted emails are always taken
from the pitch / original sender stored by the backend, never from model output (prevents an injected
"send to attacker@…" from changing the recipient).

## ADR-011 — CORS and idempotency as servlet filters

CORS moved from `WebMvcConfigurer` to a `CorsFilter` registered just after the request-id filter, so preflight and
error responses carry consistent headers before the auth interceptor runs. `IdempotencyFilter` handles the
`Idempotency-Key` header for any authenticated `POST /api/**` (key 8–100 chars, body ≤ 1 MB, multipart excluded):
the first response (2xx/4xx) is stored for 24 h per user + key + route; a replay returns it with
`Idempotent-Replayed: true`; the same key with a different body is `422 IDEMPOTENCY_KEY_REUSED`, and a repeat while
the first request is still in flight is `409 CONFLICT`. Multipart email imports are deduplicated instead by
Gmail message id or RFC 5322 `Message-ID` (unique per workspace); a pasted email without either is a new import.

## ADR-012 — Schema validation runs on PostgreSQL, not H2

H2 reports `TEXT` columns as `CLOB`, which fails Hibernate validation for perfectly correct PostgreSQL schemas.
`ddl-auto` is `validate` for production; development/demo/test-on-H2 use `none` (Flyway still builds the schema).
CI runs the full backend suite a second time on PostgreSQL 16 with `TEST_DDL_AUTO=validate`.

## ADR-013 — Stripe through its REST API, behind `BILLING_PROVIDER`

Billing is optional (`BILLING_PROVIDER=none` uses the plan stored per workspace). With `stripe`, checkout and the
customer portal are created with Stripe's REST API (form-encoded, no SDK dependency), and subscription state
changes **only** from signed webhooks (`Stripe-Signature` HMAC, 5-minute tolerance, deduplicated by event id).
The UI never infers payment status from a redirect. Plan limits live in the `plans` table and are enforced only by
`EntitlementService`.

## ADR-014 — Demo mode is a separate, labelled mode

`PITSCH_MODE=demo` wires mock Email/Calendar/Spreadsheet providers and seeds a demo account
(`demo@pitsch.app`). Mock actions are recorded as `DEMO_ACTION_SIMULATED` in the audit log and shown as
"Sent (demo, not delivered)". The AI agents still run for real (there is no fake AI output in any mode).
Production (`PITSCH_MODE` unset = production) never registers mock providers and refuses demo-only settings.

## ADR-015 — Follow-up matching: deterministic candidates + model judgement

The backend selects at most 20 candidate pitches from indexed signals (Gmail thread id, founder email, company
domain unless it is a free-mail domain, company name mentioned in subject/body). The Email Classifier receives
the candidates and decides which one (if any) the email continues, with a confidence and reason; low confidence
or ambiguity routes to human review. Embedding-based similarity was not added: the candidate signals are precise,
and a vector store would be a new dependency without a measured gain (see evals `follow_up_linking`).

## ADR-016 — Frontend: keep React + Vite, replace axios with a typed fetch client

The upgrade rewrote the SPA's pages around the new API (the old pages were bound to user-scoped endpoints and a
task-manager dashboard). axios was removed: a ~200-line fetch client provides typed responses, the backend error
envelope (`ApiError` with `requestId`), single-flight refresh on `SESSION_EXPIRED`, CSRF header and idempotency
keys. No global state library: `AuthProvider` context + per-page `useAsync`. Route-level code splitting keeps the
initial bundle at ~95 kB gzip.

## ADR-017 — Workflow recovery and retention as scheduled jobs

Daily `RETENTION_PURGE` (agent input/output payloads older than `AGENT_IO_RETENTION_DAYS`, audit events older than
`AUDIT_RETENTION_DAYS`, and — for workspaces that set `dataRetentionDays` — email bodies redacted and intermediate
workflow outputs removed after that many days; expired idempotency records and sessions are purged separately) and a 10-minute
`WORKFLOW_RECOVERY` (workflows stuck in an active state for 30 minutes without a live job are marked FAILED with
a retry action) run through the job queue, deduplicated so only one instance runs each.
