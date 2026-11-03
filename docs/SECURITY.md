# Security

Report vulnerabilities privately to the maintainers (do not open a public issue). This document describes the
threat model and the controls as implemented; [DATA_SECURITY.md](DATA_SECURITY.md) covers data handling and
[PRIVACY.md](PRIVACY.md) covers user rights.

## Assets and trust boundaries

| Asset | Where | Protection |
|---|---|---|
| Pitch emails, decks, briefs (confidential deal data) | PostgreSQL, object storage | Tenant scoping, RBAC, private bucket, presigned URLs (10 min), retention |
| Google OAuth refresh/access tokens | `integration_connections` | AES-256-GCM (`PITSCH_ENCRYPTION_KEYS`, key id per ciphertext), never logged, never sent to the AI service |
| Passwords | `users` | PBKDF2-HMAC-SHA256, 600,000 iterations (configurable, floor 210,000 in production), per-user salt |
| Sessions | `sessions`, `refresh_tokens` | Access JWT 15 min; refresh token opaque, SHA-256 hashed at rest, rotated on every use, reuse ⇒ session revoked |
| LLM / search API keys | AI service env | Only in the AI service; redacted from logs |
| Audit trail | `audit_events` | Append-only (UPDATE rejected by trigger), readable with `AUDIT_READ` only |

Untrusted input: **everything** in an email, attachment, web search result and model output. The AI service is
treated as semi-trusted: its output is validated by schema and business rules before the backend acts on it.

## Authentication

* Email + password signup with server-side password policy; email verification required in production
  (`REQUIRE_EMAIL_VERIFICATION`); optional Google sign-in (OpenID Connect, verified `email_verified`).
* Login responses are uniform for unknown user / wrong password (`INVALID_CREDENTIALS`) to prevent account
  enumeration; forgot-password always returns the same message.
* Account lockout after `MAX_FAILED_LOGINS` (5) for `LOCKOUT_MINUTES` (15), stored in the database (works across
  instances); per-IP auth rate limit `RATE_LIMIT_AUTH_PER_MINUTE`.
* Password reset and email verification tokens are single-use, hashed, short-lived; a reset revokes all sessions.
* Session/device list with revocation (`GET/DELETE /api/v1/auth/sessions`); password change revokes other sessions.
* JWT: HS256 with a ≥ 256-bit secret (`JWT_SECRET`, startup fails in production if missing/weak), issuer and
  expiry checked, session id claim checked against the database on every request (revocation is immediate).

## Authorization and tenancy

* Default-deny `AuthInterceptor`; public endpoints are explicitly annotated and pinned by a test
  (`BackendApplicationTests.PUBLIC`): health, signup/login/refresh/logout, verification and reset, invitation
  lookup, Google OAuth callback, Gmail Pub/Sub push (OIDC-verified), Stripe webhook (signature-verified).
* RBAC with permissions per role (`org/Role.java`):

| Permission | OWNER | ADMIN | INVESTOR | ANALYST | MEMBER |
|---|:-:|:-:|:-:|:-:|:-:|
| Read pitches, calendar, tasks, members | ✓ | ✓ | ✓ | ✓ | ✓ |
| Write pitches, import email, run AI workflow steps | ✓ | ✓ | ✓ | ✓ | – |
| Delete pitches, approve external actions (send email, meetings, pipeline) | ✓ | ✓ | ✓ | – | – |
| Write calendar, connect own Google account, view usage | ✓ | ✓ | ✓ | – | – |
| Manage integrations, members, workspace settings, audit log, data export | ✓ | ✓ | – | – | – |
| Billing, delete workspace | ✓ | – | – | – | – |

* The workspace comes only from the session. Every query is `…AndOrganizationId(principal.orgId())`; foreign
  ids return 404. Admins cannot grant OWNER or change an owner; the last owner cannot leave or be demoted.
* `TenantIsolationTests` checks that a second workspace can neither read, list, update nor delete the first
  workspace's pitches, emails, workflows, drafts, approvals, tasks, events, notifications and files.

## Web security

* **CSRF:** the refresh token cookie is `HttpOnly; Secure; SameSite=Lax; Path=/api/v1/auth`. Endpoints that use the
  cookie require `X-Requested-With: pitsch-web` and, when present, an allowlisted `Origin`. All other endpoints
  use the bearer token (not ambient), which is not CSRF-able.
* **CORS:** exact origin allowlist (`CORS_ALLOWED_ORIGINS`); `*` and non-https origins are refused in production.
* **Headers** (backend and nginx): HSTS (production), CSP (`default-src 'self'`, no inline scripts,
  `frame-ancestors 'none'`), `X-Frame-Options: DENY`, `nosniff`, strict referrer policy, COOP, Permissions-Policy.
* **XSS:** React escapes text; the SPA never uses `dangerouslySetInnerHTML`; links from untrusted content (deck
  URLs, research sources) are rendered only if `http(s)` (`safeHttpUrl`) with `rel="noopener noreferrer nofollow"`;
  `returnTo` redirects accept only internal paths (`safeAppPath`). Email bodies are shown as plain text.
* **SQL injection:** JPA parameter binding everywhere; the one native query takes only a timestamp; `LIKE` search
  input is escaped (`PitchController.escapeLike`); sort fields are allowlisted.
* **Errors:** `{code, message, requestId, details}`; no stack traces or exception messages leave the server
  (`server.error.include-*=never`).

## Uploads and documents

`FileValidator`: type detected from magic bytes (PDF, PPTX, text) — the client's content type and extension are
ignored; size limit per file and count per email; filenames sanitised (no path separators, control characters,
length capped); PPTX zip-bomb limits (entry count, total uncompressed size, compression ratio ≤ 100). Files are
stored under random keys, never executed, never served inline from the API origin (downloads are presigned object
storage URLs or `Content-Disposition: attachment`). A `MalwareScanner` boundary runs before storage
(`MALWARE_SCANNER=clamav` uses clamd over TCP; `none` by default — enable it for production if you accept
uploads from unknown senders). The AI service re-checks sizes and parses PDFs/PPTX with page/character limits.

## AI security

* **Instruction/data separation.** Email, document and web text is passed to models as delimited, labelled data;
  system prompts state that content inside is never an instruction. Outputs are JSON validated against Pydantic
  schemas; invalid output is retried, then fails the step (no partial or invented output).
* **Prompt-injection detection** (`app/core/injection.py`) flags common attack patterns in emails and documents;
  flagged workflows show a warning, are routed to human review and are audited (`PROMPT_INJECTION_FLAGGED`).
  Detection is a signal, not the defence — the defence is that the model cannot act.
* **No capabilities in the model.** The AI service has no Google, database, SMTP or file-system credentials and
  cannot call back into Pitsch. Agents return *intent* (draft text, slot ranking, label/pipeline suggestions). The
  backend validates intent (recipients come from stored data, labels must use the configured prefix, pipeline
  fields must be mapped, slots must be inside working hours and free/busy-clear) and requires human approval for
  every external email and calendar change.
* **Service authentication.** Backend → AI service calls carry `X-Internal-Token` (≥ 32 chars, constant-time
  compare, mandatory in production); deploy the AI service on a private network.
* **Budgets.** Per-call max output tokens, request size limit (`MAX_REQUEST_MB`), LLM timeouts and retries,
  research query/round limits and an overall research deadline, per-minute AI rate limit and monthly workspace
  quotas (`EntitlementService`) on workflows, tokens, research queries, document pages and storage.
* **Research provenance.** Only search-engine results become evidence; model knowledge is labelled
  "AI inference" and can never mark a claim SUPPORTED. Non-public URLs (private IPs, localhost, credentials in
  URL, non-http schemes) are dropped. The research agent does not fetch arbitrary URLs (SSRF surface is the
  search provider only).
* **No investment verdicts.** A guardrail rejects analysis output that contains invest/pass recommendations.

## Secrets

* Production refuses to start (`ProductionConfigValidator`) with: missing/weak `JWT_SECRET`, missing encryption
  keys, insecure cookies, email verification disabled, low hash iterations, non-PostgreSQL DB, wildcard or http
  CORS, http public URLs, missing AI token, missing SMTP, missing S3 bucket, partial Google config, partial Stripe
  config, or inline jobs. The AI service refuses to start in production without `AI_SERVICE_TOKEN`.
* Logs: structured JSON; `Authorization`, cookies, tokens, keys and passwords are never logged; the AI service
  redacts API-key patterns; email bodies and documents are not logged.
* `.env` files are git-ignored; CI runs gitleaks; `.env.example` files contain no values.
* **Rotate** any credential that was ever shared. The project ZIP this upgrade started from contained
  `ai-service/.env` with a live Gemini key (git-ignored, never committed, but distributed with the archive). It is
  not part of the upgraded tree; revoke that key in Google AI Studio and create a new one.

## Webhooks

* Gmail Pub/Sub push: Google-signed OIDC token verified (issuer, audience `GOOGLE_PUBSUB_AUDIENCE`, service account
  email); the body only triggers a history sync for the connected mailbox; deduplicated by message id.
* Stripe: `Stripe-Signature` HMAC-SHA256 with timestamp tolerance; events deduplicated by id; subscription state
  changes only from webhooks.

## Known limitations

See README "Limitations". Notably: rate limits are per instance (ADR-007); malware scanning is off unless
configured; Gmail's restricted scope requires Google's security assessment before public launch.
