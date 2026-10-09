# Changelog — Production Upgrade (October 2026)

This upgrade turns the Pitsch MVP (single-user, H2, simulated actions) into a multi-tenant SaaS while keeping its
architecture: React + Vite frontend, Spring Boot backend with `WorkflowEngine`/`AgentRunner`, FastAPI AI service with
the same eight agents. Findings that motivated each change: [PRODUCTION_AUDIT.md](PRODUCTION_AUDIT.md). Design
rationale: [ARCHITECTURAL_DECISIONS.md](ARCHITECTURAL_DECISIONS.md). The full file inventory is at the end.

## Security fixes (from the audit)

* **Leaked credential removed:** the shared ZIP contained `ai-service/.env` with a live Gemini API key (git-ignored,
  never committed). It is not part of the upgraded tree; **the key must be revoked**, since everyone who received
  the ZIP has it.
* AI service had **no authentication**: now requires `X-Internal-Token` (mandatory in production), docs off in
  production, request-size limit, request ids, nosniff.
* JWT in `localStorage` + 7-day tokens + default secret → 15-minute access token in memory, httpOnly rotating
  refresh cookie with reuse detection, DB-backed revocable sessions, startup refusal of weak/missing secrets.
* Data scoped by `user_id` only, some lookups without ownership checks → workspace tenancy enforced in every query,
  foreign ids return 404, RBAC on every endpoint (default-deny interceptor), tests for isolation and roles.
* `ActionExecutor` reported actions as done without doing them ("simulated") → removed; real providers + approvals +
  idempotent execution; mocks only in `PITSCH_MODE=demo`, labelled everywhere.
* Uploads trusted the client's content type, no limits → magic-byte detection, size/count limits, PPTX zip-bomb
  protection, filename sanitisation, optional ClamAV, object storage with presigned URLs.
* No rate limiting, no lockout, account enumeration → per-IP/user limits, DB lockout, uniform auth responses.
* Error responses could include exception messages → uniform `{code, message, requestId, details}`.
* Drafted-email recipient came from model output → always the stored founder/sender address.
* CORS `*`-capable config, no security headers → allowlist filter, HSTS/CSP/frame/nosniff headers (backend and nginx).

## Backend

* **Database:** Flyway migrations (`V1` baseline of the old schema, `V2` multi-tenancy/auth/integrations/
  approvals/jobs/billing/audit with data migration into personal workspaces, `V3` PostgreSQL trigram search + audit
  immutability trigger); `ddl-auto=validate`; PostgreSQL required in production; indexes on organization, user,
  Gmail message/thread ids, pitch, workflow, status, timestamps and external ids; uniqueness constraints for dedupe.
* **Modes:** `PITSCH_MODE=production|development|demo` (+ `test`) with per-mode property files and
  `ProductionConfigValidator`.
* **Auth:** signup/login/logout/refresh/rotation, sessions & devices, email verification, password reset and change,
  lockout, optional Google sign-in, `GET /me` with effective permissions, invitations, workspace switching.
* **Organizations:** organizations (= workspaces), memberships, roles OWNER/ADMIN/INVESTOR/ANALYST/MEMBER with a
  permission matrix, last-owner protection, workspace settings (timezone, working hours/days, meeting length,
  retention, label & sheet policies), onboarding state.
* **Integrations:** provider interfaces + Google implementations (OAuth Code + PKCE, incremental scopes, AES-GCM
  token storage, refresh, reconnect, disconnect/revoke). Gmail: Pub/Sub push with OIDC verification + History-API
  polling fallback, watch renewal, dedupe by message id, labels, in-thread send with deterministic Message-ID.
  Calendar: free/busy validation, event create/update/cancel with attendees, Meet link, reminders, idempotent event
  ids, external-change sync. Sheets: workspace pipeline sheet with field→column mapping, idempotent upsert by pitch id,
  formula-injection protection.
* **Workflow reliability:** formal state machine (`WorkflowStatus` transitions), optimistic locking, step outputs
  persisted (resume from the failed agent), retry/stop actions, recovery of stuck workflows, timeouts.
* **Background jobs:** PostgreSQL job queue (claiming, retries with backoff, dead-lettering, lock reclaim, dedupe
  keys) for classification, pipeline, meeting planning, drafting, action planning, approval execution, Gmail
  sync/ingest/watch, calendar sync, notification emails, sheet sync, workspace deletion, retention, recovery.
* **Human-in-the-loop:** `approvals` for SEND_EMAIL / CREATE_MEETING / CANCEL_MEETING / SYNC_PIPELINE /
  APPLY_LABELS; `external_operations` ledger with idempotency keys; `Idempotency-Key` HTTP filter.
* **Email ingestion:** single `EmailIngestionService` for manual (JSON, multipart) and Gmail; validation, dedupe,
  attachment storage; follow-up candidate matching by thread, founder email, company domain and company mention;
  `DELETE /api/v1/emails/{id}` erasure.
* **Pitches:** owner, deal stage, confidence, risk, claim counts, follow-up flag, version; backend search/filter/
  sort/pagination; timeline; manual create/update/delete.
* **Notifications:** typed notifications with in-app + email channels, per-type preferences, dedupe.
* **Audit:** append-only `audit_events` (actor, workspace, IP, user agent, resource, action, metadata) for auth,
  membership, email, pitch, workflow, agent, approval, external action, integration, privacy and billing events.
* **Billing foundation:** plans (FREE/PRO/TEAM/ENTERPRISE) with limits in the database, subscriptions, usage records,
  `EntitlementService` quotas (workflows, pitches, tokens, research queries, document pages, storage, members),
  optional Stripe checkout/portal/webhooks.
* **Privacy:** workspace ZIP export, account JSON export, account deletion, workspace deletion (async), email
  erasure, retention purge (agent payloads, audit, email bodies per workspace setting).
* **Observability:** request ids, structured JSON logs, `/health/live`, `/health/ready`, Prometheus metrics for
  agents, LLM tokens/cost, workflows, jobs and integrations on a separate management port.
* **API:** `/api/v1` for everything, legacy `/api/*` routes kept (workspace-scoped), OpenAPI with bearer auth,
  consistent pagination and errors; dashboard endpoint.
* **Removed:** `ActionExecutor` (simulated), `TestController`, old `ai/HealthController` (replaced by
  `observability/HealthController`), the H2-default test configuration.

## AI service

* Service settings (`PITSCH_MODE`, `AI_SERVICE_TOKEN`, `API_DOCS_ENABLED`, `MAX_REQUEST_MB`, `LLM_PRICES`); fail-fast
  configuration errors; JSON logs with request id and secret redaction; `/health/live`, `/health/ready`.
* LLM layer: per-call max output tokens, fallback model (`FallbackLLMClient`), fallback-eligible error
  classification, cost estimation, `meta.inputHash`, `meta.estimatedCostUsd`, `meta.fallbackUsed`.
* Prompt-injection detector (`app/core/injection.py`) used by the classifier and document agent; classifier exposes
  `promptInjectionSuspected` + signals.
* Research: public-URL filter (SSRF/garbage), overall deadline, budgets.
* Verification: product vocabulary `SUPPORTED/PARTIALLY_SUPPORTED/UNSUPPORTED/CONTRADICTED/NOT_FOUND` via
  `assessment`, summary counts.
* Analysis brief: new sections (problem, solution, product, business model, opportunities, fundraising, missing
  information), deterministic evidence-support score (`aiConfidence`), `generatedAt`; still no invest/pass verdict.
* Tests: `test_production_hardening.py` (auth, limits, fallback, cost, injection, URL safety, vocabulary, brief),
  offline eval test; 141 tests pass. **Evals:** `evals/run_evals.py` with offline (CI) and live suites and four
  synthetic datasets.
* Removed the nested `ai-service/.github` workflow (CI is at the repository root now).

## Frontend

* Rebuilt on the same stack (React 19, TypeScript, Vite, React Router); axios replaced by a typed fetch client
  (in-memory access token, single-flight refresh, CSRF header, idempotency keys, `ApiError` with request id).
* New information architecture: Dashboard · Inbox · Pitches · Pipeline · Workflows · Approvals · Calendar · Tasks ·
  Notifications · Integrations · Settings (profile, security/sessions, workspace, members & invitations,
  notifications, billing & usage, privacy, audit log), plus sign-up/in, verification, password reset, invitations,
  Google callback and a guided onboarding.
* Workflow view shows real agent states from the backend (no fake progress), the brief with provenance and claim
  assessments, meeting slot picker with approval, reply editor with approve-and-send, agent log with tokens/cost,
  approvals; prompt-injection warning banner; email erasure.
* Safe rendering of untrusted links, internal-only redirects, consistent loading/empty/error states with request id,
  route-level code splitting, dark design system (`src/styles/app.css`).
* Tests: Vitest (API client, URL safety, formatting, BriefView safety, route guard) — 27 tests; Playwright E2E with
  a mocked API (sign-in → workflow → brief → approve reply; dashboard; session redirect) — 3 tests.

## Delivery

* Dockerfiles for all three services (multi-stage, non-root, health checks), nginx config with security headers and
  `/api` proxy, `docker-compose.yml` (PostgreSQL, MinIO, optional ClamAV), `scripts/gen-dev-env.sh`.
* GitHub Actions: CI (frontend lint/build/unit/E2E, backend on H2 and on PostgreSQL with schema validation, AI tests
  + evals, gitleaks, npm audit, pip-audit, Trivy, Docker builds), CodeQL, release to GHCR gated on CI, Dependabot.
* `.env.example` for root/compose, backend, AI service and frontend; updated `start-dev.sh` / `start-dev.bat`.
* Documentation: README rewrite; ARCHITECTURE, SECURITY, DATA_SECURITY, PRIVACY, DEPLOYMENT, API, AI_ARCHITECTURE,
  INTEGRATIONS, OPERATIONS, CONTRIBUTING, ARCHITECTURAL_DECISIONS, PRODUCTION_AUDIT, this changelog.

## Verification status

| Check | Result |
|---|---|
| Frontend lint / type-check / build | pass |
| Frontend unit tests (Vitest) | 27 pass |
| Frontend E2E (Playwright, mocked API) | 3 pass |
| AI service tests (pytest) | 141 pass |
| AI offline evals | all thresholds met |
| AI service production-mode startup | refuses to start without token; health, 401 without token, docs hidden — verified |
| Backend compilation (main + tests) | compiles with zero errors against API-compatible stubs |
| Backend schema check | every entity field/type matches the Flyway-migrated PostgreSQL 16 schema; repository query names resolve |
| Backend unit tests (security, domain, billing, ingestion, pitch) | pass (stub runner) |
| Backend Spring integration tests (auth, tenant isolation, idempotency, workflow flow, app) | written; **not executed in the build environment** — Maven Central was blocked by its network policy. CI runs them on H2 and PostgreSQL. |
| Docker image builds | Dockerfiles written; **not built in the build environment** (Docker Hub blocked). CI builds them. |

## File inventory

Generated by comparing the upgraded tree with the original ZIP (excluding `.git` and `node_modules`).

### Added (243)

<details><summary><code>(root)</code> — 3 files</summary>

- `.env.example`
- `.gitleaks.toml`
- `docker-compose.yml`

</details>

<details><summary><code>.github</code> — 4 files</summary>

- `.github/dependabot.yml`
- `.github/workflows/ci.yml`
- `.github/workflows/codeql.yml`
- `.github/workflows/release.yml`

</details>

<details><summary><code>ai-service</code> — 12 files</summary>

- `ai-service/.dockerignore`
- `ai-service/Dockerfile`
- `ai-service/app/core/injection.py`
- `ai-service/evals/__init__.py`
- `ai-service/evals/datasets/classifier.jsonl`
- `ai-service/evals/datasets/guardrails.jsonl`
- `ai-service/evals/datasets/injection.jsonl`
- `ai-service/evals/datasets/verification.jsonl`
- `ai-service/evals/run_evals.py`
- `ai-service/tests/conftest.py`
- `ai-service/tests/test_evals_offline.py`
- `ai-service/tests/test_production_hardening.py`

</details>

<details><summary><code>backend</code> — 171 files</summary>

- `backend/.dockerignore`
- `backend/Dockerfile`
- `backend/src/main/java/com/pitsch/backend/actions/ActionJobHandlers.java`
- `backend/src/main/java/com/pitsch/backend/actions/ActionPlanner.java`
- `backend/src/main/java/com/pitsch/backend/actions/ActionService.java`
- `backend/src/main/java/com/pitsch/backend/approval/Approval.java`
- `backend/src/main/java/com/pitsch/backend/approval/ApprovalController.java`
- `backend/src/main/java/com/pitsch/backend/approval/ApprovalRepository.java`
- `backend/src/main/java/com/pitsch/backend/approval/ApprovalService.java`
- `backend/src/main/java/com/pitsch/backend/approval/ApprovalType.java`
- `backend/src/main/java/com/pitsch/backend/audit/AuditAction.java`
- `backend/src/main/java/com/pitsch/backend/audit/AuditController.java`
- `backend/src/main/java/com/pitsch/backend/audit/AuditEvent.java`
- `backend/src/main/java/com/pitsch/backend/audit/AuditEventRepository.java`
- `backend/src/main/java/com/pitsch/backend/audit/AuditService.java`
- `backend/src/main/java/com/pitsch/backend/auth/AllowUnverified.java`
- `backend/src/main/java/com/pitsch/backend/auth/AllowWithoutWorkspace.java`
- `backend/src/main/java/com/pitsch/backend/auth/AuthPrincipal.java`
- `backend/src/main/java/com/pitsch/backend/auth/AuthPrincipalResolver.java`
- `backend/src/main/java/com/pitsch/backend/auth/AuthService.java`
- `backend/src/main/java/com/pitsch/backend/auth/CsrfGuard.java`
- `backend/src/main/java/com/pitsch/backend/auth/MeService.java`
- `backend/src/main/java/com/pitsch/backend/auth/Permission.java`
- `backend/src/main/java/com/pitsch/backend/auth/PublicEndpoint.java`
- `backend/src/main/java/com/pitsch/backend/auth/RefreshToken.java`
- `backend/src/main/java/com/pitsch/backend/auth/RefreshTokenRepository.java`
- `backend/src/main/java/com/pitsch/backend/auth/RequiresPermission.java`
- `backend/src/main/java/com/pitsch/backend/auth/Session.java`
- `backend/src/main/java/com/pitsch/backend/auth/SessionCookies.java`
- `backend/src/main/java/com/pitsch/backend/auth/SessionRepository.java`
- `backend/src/main/java/com/pitsch/backend/auth/SessionService.java`
- `backend/src/main/java/com/pitsch/backend/auth/UserToken.java`
- `backend/src/main/java/com/pitsch/backend/auth/UserTokenRepository.java`
- `backend/src/main/java/com/pitsch/backend/billing/BillingController.java`
- `backend/src/main/java/com/pitsch/backend/billing/BillingService.java`
- `backend/src/main/java/com/pitsch/backend/billing/EntitlementService.java`
- `backend/src/main/java/com/pitsch/backend/billing/Plan.java`
- `backend/src/main/java/com/pitsch/backend/billing/PlanRepository.java`
- `backend/src/main/java/com/pitsch/backend/billing/StripeClient.java`
- `backend/src/main/java/com/pitsch/backend/billing/Subscription.java`
- `backend/src/main/java/com/pitsch/backend/billing/SubscriptionRepository.java`
- `backend/src/main/java/com/pitsch/backend/billing/UsageMetric.java`
- `backend/src/main/java/com/pitsch/backend/billing/UsageRecord.java`
- `backend/src/main/java/com/pitsch/backend/billing/UsageRecordRepository.java`
- `backend/src/main/java/com/pitsch/backend/billing/UsageService.java`
- `backend/src/main/java/com/pitsch/backend/common/ClientInfo.java`
- `backend/src/main/java/com/pitsch/backend/common/ErrorCode.java`
- `backend/src/main/java/com/pitsch/backend/common/ErrorResponse.java`
- `backend/src/main/java/com/pitsch/backend/common/Hashing.java`
- `backend/src/main/java/com/pitsch/backend/common/PageResponse.java`
- `backend/src/main/java/com/pitsch/backend/common/Pagination.java`
- `backend/src/main/java/com/pitsch/backend/common/RateLimiter.java`
- `backend/src/main/java/com/pitsch/backend/common/RequestContext.java`
- `backend/src/main/java/com/pitsch/backend/config/CorsConfig.java`
- `backend/src/main/java/com/pitsch/backend/config/OpenApiConfig.java`
- `backend/src/main/java/com/pitsch/backend/config/PitschProperties.java`
- `backend/src/main/java/com/pitsch/backend/config/ProductionConfigValidator.java`
- `backend/src/main/java/com/pitsch/backend/dashboard/DashboardController.java`
- `backend/src/main/java/com/pitsch/backend/email/EmailDeletionService.java`
- `backend/src/main/java/com/pitsch/backend/email/EmailIngestionService.java`
- `backend/src/main/java/com/pitsch/backend/files/ClamAvScanner.java`
- `backend/src/main/java/com/pitsch/backend/files/FileService.java`
- `backend/src/main/java/com/pitsch/backend/files/FileValidator.java`
- `backend/src/main/java/com/pitsch/backend/files/LocalFileController.java`
- `backend/src/main/java/com/pitsch/backend/files/LocalStorageProvider.java`
- `backend/src/main/java/com/pitsch/backend/files/MalwareScanner.java`
- `backend/src/main/java/com/pitsch/backend/files/NoopMalwareScanner.java`
- `backend/src/main/java/com/pitsch/backend/files/S3StorageProvider.java`
- `backend/src/main/java/com/pitsch/backend/files/StorageConfig.java`
- `backend/src/main/java/com/pitsch/backend/files/StorageProvider.java`
- `backend/src/main/java/com/pitsch/backend/files/StoredFile.java`
- `backend/src/main/java/com/pitsch/backend/files/StoredFileRepository.java`
- `backend/src/main/java/com/pitsch/backend/idempotency/ExternalOperation.java`
- `backend/src/main/java/com/pitsch/backend/idempotency/ExternalOperationRepository.java`
- `backend/src/main/java/com/pitsch/backend/idempotency/ExternalOperationService.java`
- `backend/src/main/java/com/pitsch/backend/idempotency/IdempotencyFilter.java`
- `backend/src/main/java/com/pitsch/backend/idempotency/IdempotencyRecord.java`
- `backend/src/main/java/com/pitsch/backend/idempotency/IdempotencyRecordRepository.java`
- `backend/src/main/java/com/pitsch/backend/integration/InboundEvent.java`
- `backend/src/main/java/com/pitsch/backend/integration/InboundEventRepository.java`
- `backend/src/main/java/com/pitsch/backend/integration/InboundEventService.java`
- `backend/src/main/java/com/pitsch/backend/integration/Integration.java`
- `backend/src/main/java/com/pitsch/backend/integration/IntegrationConnection.java`
- `backend/src/main/java/com/pitsch/backend/integration/IntegrationConnectionRepository.java`
- `backend/src/main/java/com/pitsch/backend/integration/IntegrationController.java`
- `backend/src/main/java/com/pitsch/backend/integration/IntegrationException.java`
- `backend/src/main/java/com/pitsch/backend/integration/IntegrationService.java`
- `backend/src/main/java/com/pitsch/backend/integration/OAuthState.java`
- `backend/src/main/java/com/pitsch/backend/integration/OAuthStateRepository.java`
- `backend/src/main/java/com/pitsch/backend/integration/ProviderRegistry.java`
- `backend/src/main/java/com/pitsch/backend/integration/google/CalendarSyncService.java`
- `backend/src/main/java/com/pitsch/backend/integration/google/GmailJobHandlers.java`
- `backend/src/main/java/com/pitsch/backend/integration/google/GmailProvider.java`
- `backend/src/main/java/com/pitsch/backend/integration/google/GmailPushController.java`
- `backend/src/main/java/com/pitsch/backend/integration/google/GmailSyncService.java`
- `backend/src/main/java/com/pitsch/backend/integration/google/GoogleApiClient.java`
- `backend/src/main/java/com/pitsch/backend/integration/google/GoogleCalendarProvider.java`
- `backend/src/main/java/com/pitsch/backend/integration/google/GoogleIdTokenVerifier.java`
- `backend/src/main/java/com/pitsch/backend/integration/google/GoogleOAuthClient.java`
- `backend/src/main/java/com/pitsch/backend/integration/google/GoogleSheetsProvider.java`
- `backend/src/main/java/com/pitsch/backend/integration/mock/MockCalendarProvider.java`
- `backend/src/main/java/com/pitsch/backend/integration/mock/MockEmailProvider.java`
- `backend/src/main/java/com/pitsch/backend/integration/mock/MockSpreadsheetProvider.java`
- `backend/src/main/java/com/pitsch/backend/integration/provider/CalendarProvider.java`
- `backend/src/main/java/com/pitsch/backend/integration/provider/EmailProvider.java`
- `backend/src/main/java/com/pitsch/backend/integration/provider/SpreadsheetProvider.java`
- `backend/src/main/java/com/pitsch/backend/integration/sheets/PipelineField.java`
- `backend/src/main/java/com/pitsch/backend/integration/sheets/PipelineSheetConfig.java`
- `backend/src/main/java/com/pitsch/backend/integration/sheets/PipelineSheetConfigRepository.java`
- `backend/src/main/java/com/pitsch/backend/integration/sheets/PipelineSheetController.java`
- `backend/src/main/java/com/pitsch/backend/integration/sheets/PipelineSheetRow.java`
- `backend/src/main/java/com/pitsch/backend/integration/sheets/PipelineSheetRowRepository.java`
- `backend/src/main/java/com/pitsch/backend/integration/sheets/PipelineSheetSyncHandler.java`
- `backend/src/main/java/com/pitsch/backend/integration/sheets/PipelineSyncService.java`
- `backend/src/main/java/com/pitsch/backend/jobs/Job.java`
- `backend/src/main/java/com/pitsch/backend/jobs/JobHandler.java`
- `backend/src/main/java/com/pitsch/backend/jobs/JobQueue.java`
- `backend/src/main/java/com/pitsch/backend/jobs/JobRepository.java`
- `backend/src/main/java/com/pitsch/backend/jobs/JobRunner.java`
- `backend/src/main/java/com/pitsch/backend/jobs/JobType.java`
- `backend/src/main/java/com/pitsch/backend/mail/EmailTemplates.java`
- `backend/src/main/java/com/pitsch/backend/mail/LogMailService.java`
- `backend/src/main/java/com/pitsch/backend/mail/MailConfig.java`
- `backend/src/main/java/com/pitsch/backend/mail/MailMessage.java`
- `backend/src/main/java/com/pitsch/backend/mail/MailService.java`
- `backend/src/main/java/com/pitsch/backend/mail/SmtpMailService.java`
- `backend/src/main/java/com/pitsch/backend/notification/NotificationEmailHandler.java`
- `backend/src/main/java/com/pitsch/backend/notification/NotificationPreference.java`
- `backend/src/main/java/com/pitsch/backend/notification/NotificationPreferenceRepository.java`
- `backend/src/main/java/com/pitsch/backend/notification/NotificationType.java`
- `backend/src/main/java/com/pitsch/backend/observability/HealthController.java`
- `backend/src/main/java/com/pitsch/backend/observability/PitschMetrics.java`
- `backend/src/main/java/com/pitsch/backend/observability/RequestIdFilter.java`
- `backend/src/main/java/com/pitsch/backend/observability/SecurityHeadersFilter.java`
- `backend/src/main/java/com/pitsch/backend/org/Invitation.java`
- `backend/src/main/java/com/pitsch/backend/org/InvitationRepository.java`
- `backend/src/main/java/com/pitsch/backend/org/Membership.java`
- `backend/src/main/java/com/pitsch/backend/org/MembershipRepository.java`
- `backend/src/main/java/com/pitsch/backend/org/Organization.java`
- `backend/src/main/java/com/pitsch/backend/org/OrganizationRepository.java`
- `backend/src/main/java/com/pitsch/backend/org/OrganizationService.java`
- `backend/src/main/java/com/pitsch/backend/org/Role.java`
- `backend/src/main/java/com/pitsch/backend/org/WorkspaceController.java`
- `backend/src/main/java/com/pitsch/backend/pitch/DealStage.java`
- `backend/src/main/java/com/pitsch/backend/pitch/PitchService.java`
- `backend/src/main/java/com/pitsch/backend/privacy/DataExportService.java`
- `backend/src/main/java/com/pitsch/backend/privacy/DeletionService.java`
- `backend/src/main/java/com/pitsch/backend/privacy/MaintenanceService.java`
- `backend/src/main/java/com/pitsch/backend/privacy/PrivacyController.java`
- `backend/src/main/java/com/pitsch/backend/security/TokenCipher.java`
- `backend/src/main/java/com/pitsch/backend/workflow/RetryableStepException.java`
- `backend/src/main/java/com/pitsch/backend/workflow/WorkflowJobHandlers.java`
- `backend/src/main/java/com/pitsch/backend/workflow/WorkflowSteps.java`
- `backend/src/main/java/com/pitsch/backend/workflow/WorkflowStore.java`
- `backend/src/main/resources/application-demo.properties`
- `backend/src/main/resources/application-development.properties`
- `backend/src/main/resources/application-production.properties`
- `backend/src/main/resources/db/migration/common/V1__baseline.sql`
- `backend/src/main/resources/db/migration/common/V2__multitenancy_auth_integrations.sql`
- `backend/src/main/resources/db/migration/h2/V3__postgres_search_and_audit_immutability.sql`
- `backend/src/main/resources/db/migration/postgresql/V3__postgres_search_and_audit_immutability.sql`
- `backend/src/test/java/com/pitsch/backend/AuthTests.java`
- `backend/src/test/java/com/pitsch/backend/IdempotencyTests.java`
- `backend/src/test/java/com/pitsch/backend/TenantIsolationTests.java`
- `backend/src/test/java/com/pitsch/backend/TestAiConfig.java`
- `backend/src/test/java/com/pitsch/backend/billing/BillingUnitTests.java`
- `backend/src/test/java/com/pitsch/backend/email/IngestionUnitTests.java`
- `backend/src/test/java/com/pitsch/backend/pitch/PitchUnitTests.java`
- `backend/src/test/java/com/pitsch/backend/unit/DomainUnitTests.java`
- `backend/src/test/java/com/pitsch/backend/unit/SecurityUnitTests.java`
- `backend/src/test/resources/application-test.properties`

</details>

<details><summary><code>docs</code> — 13 files</summary>

- `docs/AI_ARCHITECTURE.md`
- `docs/API.md`
- `docs/ARCHITECTURAL_DECISIONS.md`
- `docs/ARCHITECTURE.md`
- `docs/CHANGELOG_PRODUCTION_UPGRADE.md`
- `docs/CONTRIBUTING.md`
- `docs/DATA_SECURITY.md`
- `docs/DEPLOYMENT.md`
- `docs/INTEGRATIONS.md`
- `docs/OPERATIONS.md`
- `docs/PRIVACY.md`
- `docs/PRODUCTION_AUDIT.md`
- `docs/SECURITY.md`

</details>

<details><summary><code>frontend</code> — 39 files</summary>

- `frontend/.dockerignore`
- `frontend/Dockerfile`
- `frontend/docker/nginx.conf.template`
- `frontend/e2e/journey.spec.ts`
- `frontend/e2e/mockApi.ts`
- `frontend/playwright.config.ts`
- `frontend/src/components/BriefView.tsx`
- `frontend/src/components/ImportEmailModal.tsx`
- `frontend/src/components/badges.tsx`
- `frontend/src/components/layout/AppShell.tsx`
- `frontend/src/components/ui.tsx`
- `frontend/src/lib/api/client.ts`
- `frontend/src/lib/api/endpoints.ts`
- `frontend/src/lib/api/types.ts`
- `frontend/src/lib/auth/AuthContext.tsx`
- `frontend/src/lib/format.ts`
- `frontend/src/lib/hooks.ts`
- `frontend/src/lib/safeUrl.ts`
- `frontend/src/pages/Approvals.tsx`
- `frontend/src/pages/Calendar.tsx`
- `frontend/src/pages/Dashboard.tsx`
- `frontend/src/pages/Inbox.tsx`
- `frontend/src/pages/Integrations.tsx`
- `frontend/src/pages/Notifications.tsx`
- `frontend/src/pages/Onboarding.tsx`
- `frontend/src/pages/Pipeline.tsx`
- `frontend/src/pages/PitchDetail.tsx`
- `frontend/src/pages/Pitches.tsx`
- `frontend/src/pages/Tasks.tsx`
- `frontend/src/pages/WorkflowDetail.tsx`
- `frontend/src/pages/Workflows.tsx`
- `frontend/src/pages/auth/AuthPages.tsx`
- `frontend/src/styles/app.css`
- `frontend/src/test/client.test.ts`
- `frontend/src/test/components.test.tsx`
- `frontend/src/test/format.test.ts`
- `frontend/src/test/safeUrl.test.ts`
- `frontend/src/vite-env.d.ts`
- `frontend/vitest.config.ts`

</details>

<details><summary><code>scripts</code> — 1 files</summary>

- `scripts/gen-dev-env.sh`

</details>

### Modified (98)

<details><summary><code>(root)</code> — 3 files</summary>

- `README.md`
- `start-dev.bat`
- `start-dev.sh`

</details>

<details><summary><code>ai-service</code> — 23 files</summary>

- `ai-service/.env.example`
- `ai-service/README.md`
- `ai-service/app/agents/analysis/agent.py`
- `ai-service/app/agents/analysis/prompt.py`
- `ai-service/app/agents/analysis/render.py`
- `ai-service/app/agents/analysis/schemas.py`
- `ai-service/app/agents/base.py`
- `ai-service/app/agents/classifier/agent.py`
- `ai-service/app/agents/classifier/schemas.py`
- `ai-service/app/agents/document/agent.py`
- `ai-service/app/agents/research/agent.py`
- `ai-service/app/agents/verification/agent.py`
- `ai-service/app/agents/verification/schemas.py`
- `ai-service/app/api/routes.py`
- `ai-service/app/config.py`
- `ai-service/app/core/errors.py`
- `ai-service/app/core/llm.py`
- `ai-service/app/core/logging.py`
- `ai-service/app/main.py`
- `ai-service/app/schemas/common.py`
- `ai-service/docs/integration.md`
- `ai-service/evals/README.md`
- `ai-service/tests/test_analysis.py`

</details>

<details><summary><code>backend</code> — 60 files</summary>

- `backend/.env.example`
- `backend/pom.xml`
- `backend/src/main/java/com/pitsch/backend/BackendApplication.java`
- `backend/src/main/java/com/pitsch/backend/activity/Activity.java`
- `backend/src/main/java/com/pitsch/backend/activity/ActivityController.java`
- `backend/src/main/java/com/pitsch/backend/activity/ActivityRepository.java`
- `backend/src/main/java/com/pitsch/backend/activity/ActivityService.java`
- `backend/src/main/java/com/pitsch/backend/ai/AgentResult.java`
- `backend/src/main/java/com/pitsch/backend/ai/HttpAiClient.java`
- `backend/src/main/java/com/pitsch/backend/auth/AuthController.java`
- `backend/src/main/java/com/pitsch/backend/auth/AuthInterceptor.java`
- `backend/src/main/java/com/pitsch/backend/auth/DemoDataSeeder.java`
- `backend/src/main/java/com/pitsch/backend/auth/JwtService.java`
- `backend/src/main/java/com/pitsch/backend/auth/PasswordHasher.java`
- `backend/src/main/java/com/pitsch/backend/auth/User.java`
- `backend/src/main/java/com/pitsch/backend/auth/UserView.java`
- `backend/src/main/java/com/pitsch/backend/common/ApiException.java`
- `backend/src/main/java/com/pitsch/backend/common/GlobalExceptionHandler.java`
- `backend/src/main/java/com/pitsch/backend/common/Json.java`
- `backend/src/main/java/com/pitsch/backend/config/AppConfig.java`
- `backend/src/main/java/com/pitsch/backend/config/WebConfig.java`
- `backend/src/main/java/com/pitsch/backend/email/Email.java`
- `backend/src/main/java/com/pitsch/backend/email/EmailAttachment.java`
- `backend/src/main/java/com/pitsch/backend/email/EmailAttachmentRepository.java`
- `backend/src/main/java/com/pitsch/backend/email/EmailController.java`
- `backend/src/main/java/com/pitsch/backend/email/EmailRepository.java`
- `backend/src/main/java/com/pitsch/backend/event/CalendarEvent.java`
- `backend/src/main/java/com/pitsch/backend/event/CalendarEventRepository.java`
- `backend/src/main/java/com/pitsch/backend/event/EventController.java`
- `backend/src/main/java/com/pitsch/backend/notification/Notification.java`
- `backend/src/main/java/com/pitsch/backend/notification/NotificationController.java`
- `backend/src/main/java/com/pitsch/backend/notification/NotificationRepository.java`
- `backend/src/main/java/com/pitsch/backend/notification/NotificationService.java`
- `backend/src/main/java/com/pitsch/backend/pitch/Pitch.java`
- `backend/src/main/java/com/pitsch/backend/pitch/PitchController.java`
- `backend/src/main/java/com/pitsch/backend/pitch/PitchRepository.java`
- `backend/src/main/java/com/pitsch/backend/task/Task.java`
- `backend/src/main/java/com/pitsch/backend/task/TaskController.java`
- `backend/src/main/java/com/pitsch/backend/task/TaskRepository.java`
- `backend/src/main/java/com/pitsch/backend/user/UserController.java`
- `backend/src/main/java/com/pitsch/backend/user/UserSettingsRepository.java`
- `backend/src/main/java/com/pitsch/backend/workflow/AgentExecution.java`
- `backend/src/main/java/com/pitsch/backend/workflow/AgentExecutionRepository.java`
- `backend/src/main/java/com/pitsch/backend/workflow/AgentInputs.java`
- `backend/src/main/java/com/pitsch/backend/workflow/AgentRunner.java`
- `backend/src/main/java/com/pitsch/backend/workflow/CalendarController.java`
- `backend/src/main/java/com/pitsch/backend/workflow/DraftController.java`
- `backend/src/main/java/com/pitsch/backend/workflow/EmailDraft.java`
- `backend/src/main/java/com/pitsch/backend/workflow/EmailDraftRepository.java`
- `backend/src/main/java/com/pitsch/backend/workflow/Workflow.java`
- `backend/src/main/java/com/pitsch/backend/workflow/WorkflowController.java`
- `backend/src/main/java/com/pitsch/backend/workflow/WorkflowEngine.java`
- `backend/src/main/java/com/pitsch/backend/workflow/WorkflowRepository.java`
- `backend/src/main/java/com/pitsch/backend/workflow/WorkflowStatus.java`
- `backend/src/main/java/com/pitsch/backend/workflow/WorkflowViews.java`
- `backend/src/main/resources/application.properties`
- `backend/src/test/java/com/pitsch/backend/BackendApplicationTests.java`
- `backend/src/test/java/com/pitsch/backend/FakeAiClient.java`
- `backend/src/test/java/com/pitsch/backend/TestSupport.java`
- `backend/src/test/java/com/pitsch/backend/WorkflowFlowTests.java`

</details>

<details><summary><code>frontend</code> — 12 files</summary>

- `frontend/.env.example`
- `frontend/.gitignore`
- `frontend/README.md`
- `frontend/eslint.config.js`
- `frontend/index.html`
- `frontend/package-lock.json`
- `frontend/package.json`
- `frontend/src/App.tsx`
- `frontend/src/main.tsx`
- `frontend/src/pages/settings/Settings.tsx`
- `frontend/tsconfig.node.json`
- `frontend/vite.config.ts`

</details>

### Removed (52)

<details><summary><code>ai-service</code> — 2 files</summary>

- `ai-service/.env`
- `ai-service/.github/workflows/tests.yml`

</details>

<details><summary><code>backend</code> — 5 files</summary>

- `backend/src/main/java/com/pitsch/backend/ai/HealthController.java`
- `backend/src/main/java/com/pitsch/backend/controller/TestController.java`
- `backend/src/main/java/com/pitsch/backend/workflow/ActionExecutor.java`
- `backend/src/test/java/com/pitsch/backend/AuthAndCrudTests.java`
- `backend/src/test/resources/application.properties`

</details>

<details><summary><code>frontend</code> — 45 files</summary>

- `frontend/src/App.css`
- `frontend/src/components/layout/AppLayout.tsx`
- `frontend/src/components/layout/Navbar.tsx`
- `frontend/src/components/layout/PageContainer.tsx`
- `frontend/src/components/layout/ProtectedRoute.tsx`
- `frontend/src/components/layout/Sidebar.tsx`
- `frontend/src/components/ui/Badge.tsx`
- `frontend/src/components/ui/Button.tsx`
- `frontend/src/components/ui/Card.tsx`
- `frontend/src/components/ui/EmptyState.tsx`
- `frontend/src/components/ui/ErrorState.tsx`
- `frontend/src/components/ui/Icon.tsx`
- `frontend/src/components/ui/Input.tsx`
- `frontend/src/components/ui/Loading.tsx`
- `frontend/src/components/ui/Modal.tsx`
- `frontend/src/hooks/useClickOutside.ts`
- `frontend/src/hooks/useCurrentUser.ts`
- `frontend/src/hooks/useFetch.ts`
- `frontend/src/hooks/useLogout.ts`
- `frontend/src/hooks/usePolling.ts`
- `frontend/src/index.css`
- `frontend/src/pages/auth/Login.tsx`
- `frontend/src/pages/calendar/Calendar.tsx`
- `frontend/src/pages/calendar/EventFormModal.tsx`
- `frontend/src/pages/calendar/MonthGrid.tsx`
- `frontend/src/pages/dashboard/Dashboard.tsx`
- `frontend/src/pages/pitches/BriefView.tsx`
- `frontend/src/pages/pitches/Pitches.tsx`
- `frontend/src/pages/pitches/SubmitEmailModal.tsx`
- `frontend/src/pages/pitches/WorkflowDetail.tsx`
- `frontend/src/pages/pitches/WorkflowPanels.tsx`
- `frontend/src/pages/pitches/pitches.css`
- `frontend/src/pages/tasks/TaskFormModal.tsx`
- `frontend/src/pages/tasks/Tasks.tsx`
- `frontend/src/services/activityService.ts`
- `frontend/src/services/api.ts`
- `frontend/src/services/authService.ts`
- `frontend/src/services/calendarService.ts`
- `frontend/src/services/healthService.ts`
- `frontend/src/services/notificationService.ts`
- `frontend/src/services/taskService.ts`
- `frontend/src/services/userService.ts`
- `frontend/src/services/workflowService.ts`
- `frontend/src/types/index.ts`
- `frontend/src/utils/format.ts`

</details>
