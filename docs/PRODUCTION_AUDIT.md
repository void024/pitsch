# Pitsch — Production Audit

Audit date: 2026-10-05 · Scope: the complete repository as delivered (`frontend/`, `backend/`, `ai-service/`,
root scripts and docs) · Baseline checks run before any change: AI service `pytest` (111 passed), frontend
`eslint` (clean) and `vite build` (OK). The backend test suite could not be executed in the audit environment
(Maven Central was blocked by the environment's egress policy); it was reviewed by reading.

Severity: **P0** = must fix before any production traffic · **P1** = fix before general availability ·
**P2** = important but not blocking.

---

## 1. Current architecture (as found)

```
React SPA (Vite, axios)  ──REST + JWT (localStorage)──►  Spring Boot 3.5 (single process)
                                                           ├─ AuthInterceptor (custom HS256 JWT)
                                                           ├─ Controllers (/api/**)  ── user_id scoped JPA repos ──► H2 file DB (default) / PostgreSQL
                                                           ├─ WorkflowEngine (in-memory locks, fixed thread pool)
                                                           │     └─ AgentRunner ──HTTP──► FastAPI AI service (8 agents, no auth)
                                                           └─ ActionExecutor ("simulated": writes rows only)          ├─ OpenAI-compatible LLM (Gemini)
                                                                                                                      └─ Tavily search
```

* **Frontend** — React 19 + TS + Vite + axios + react-router. Pages: Login, Dashboard (task-manager style),
  Tasks, Calendar (local events), Pitches (workflow list), WorkflowDetail (decision panel, brief, slots, draft,
  agent progress), Settings. Token in `localStorage`. Polling every 2.5–3 s while a workflow is active.
* **Backend** — one Spring Boot module, package-by-feature: `auth`, `user`, `email`, `pitch`, `workflow`,
  `event`, `task`, `notification`, `activity`, `ai`, `common`, `config`. Schema created by
  `ddl-auto=update`. One workflow per incoming email; agent outputs stored verbatim as JSON text columns.
* **AI service** — FastAPI, 8 agents behind `/agents/*`, common `AgentRequest/AgentResult` envelope,
  `call_structured` (JSON-mode LLM call + Pydantic validation + corrective re-ask + backoff), deterministic
  post-processing in every agent, structured JSON logs without content.

### Existing functionality (verified by reading code and tests)

| Capability | Status |
|---|---|
| Email/password signup + login, JWT, `/auth/me` | Works (no refresh, no revocation) |
| Submit an email (UI/API) → AI classification (new / follow-up / not pitch) | Works |
| Follow-up matching (thread, founder email, domain, company mention) | Works (deterministic signals + LLM choice among candidates) |
| Document → Research → Verification → Analysis pipeline | Works, async on a thread pool |
| Research brief with claims matrix, provenance, sources, no recommendation | Works |
| Calendar Agent slot ranking (deterministic optimiser, LLM only parses free text) | Works |
| Email Response Agent drafts (placeholders for times, commitment/link checks) | Works |
| Approval flows: pick slot → "meeting", edit draft → "send" | Works, **but external effects are simulated** |
| Gmail labels / Sheets rows / Calendar events / sent emails | **Simulated** (rows in Pitsch DB only) |
| Tasks, local calendar events, notifications, activity feed | Works (per user) |

---

## 2. Production strengths (keep these)

1. **AI service engineering is genuinely strong.** Untrusted text is fenced (`fence()` neutralises the
   closing tag), every quote/excerpt is checked against the real source text, model-produced IDs are never
   trusted (only IDs handed out by code are accepted), research has a hard query budget and round limit,
   provenance (PITCH / COMPANY / EXTERNAL / AI_INFERENCE) is derived by code, and a regex guardrail turns
   investment-recommendation language into a validation error that triggers a re-ask.
2. **Deterministic where it matters.** Slot optimisation, action planning, claim-status enforcement
   ("VERIFIED needs an EXTERNAL source"), and brief assembly are code, not prompts.
3. **Clear orchestration boundary.** Only the backend calls the AI service; the frontend never touches
   LLMs or Google. Agents never hold credentials.
4. **Agent execution records** (`agent_executions`) already capture status, tokens, latency, errors.
5. **Human-in-the-loop UX intent** is explicit: the UI never sends or books without a click.
6. **Tests that pin the contract**: the backend replays the AI service's recorded examples.

---

## 3. Findings

### 3.1 Security

| # | Sev | Finding | Where |
|---|---|---|---|
| S1 | P0 | A live LLM API key was present in `ai-service/.env` inside the distributed archive (git-ignored, but shipped). | `ai-service/.env` |
| S2 | P0 | Default JWT secret committed and accepted in every environment (only a log warning). | `application.properties`, `JwtService` |
| S3 | P0 | Demo user `demo@pitsch.com / pitsch123` is seeded by default and advertised on the login page. | `DemoDataSeeder`, `Login.tsx` |
| S4 | P0 | AI service has **no authentication**; anyone reaching port 8000 can spend LLM/search budget. FastAPI `/docs` exposed. | `ai-service/app/main.py` |
| S5 | P0 | **Approval gate is not backend-authoritative**: `ActionExecutor` trusts the `approved` flag returned by the AI service, and the AI service sets it from whatever `approval` object the caller sent. | `ActionExecutor.execute`, `action/agent.py` |
| S6 | P0 | No tenancy model: everything is scoped by `user_id` only — no organizations, roles, or RBAC. | all repositories |
| S7 | P1 | JWT: 24 h lifetime, no refresh, no revocation (logout is a no-op), stored in `localStorage` (exfiltratable by any XSS), no `iss`/`aud`. | `JwtService`, `api.ts` |
| S8 | P1 | No login rate limiting or account lockout; PBKDF2 at 120k iterations (OWASP 2023: 600k for PBKDF2-SHA256). | `AuthController`, `PasswordHasher` |
| S9 | P1 | No email verification or password reset; profile email can be changed without re-authentication. | `UserController.updateProfile` |
| S10 | P1 | Uploads: base64 in a DB `TEXT` column, no magic-byte/type validation, no malware-scan boundary, no zip-bomb protection for PPTX, filename not sanitised. | `EmailController`, `EmailAttachment` |
| S11 | P1 | Mass assignment: `POST /api/pitches` binds the JPA entity directly from the request body. | `PitchController.create` |
| S12 | P1 | Research source URLs are rendered as `<a href>` without a scheme allowlist (`javascript:` would execute). | `BriefView.tsx` |
| S13 | P1 | No security headers (CSP, HSTS, nosniff, frame-ancestors) and 4xx errors echo raw framework messages. | `GlobalExceptionHandler` |
| S14 | P2 | `agent_executions.input_json` stores full email bodies and research forever (no retention). | `AgentRunner` |
| S15 | P2 | Prompt-injection attempts are resisted (fencing) but never *flagged* to the user. | classifier |

### 3.2 Correctness bugs

| # | Sev | Finding |
|---|---|---|
| B1 | P0 | **Silent non-send.** Action IDs are `sha1(workflow, event, type, target)`. A second approved email to the same founder within one workflow gets the same ID, is skipped as "already executed", yet the draft is marked `SENT`. Same for a second meeting (`target=calendar:primary`). |
| B2 | P0 | **Misleading statuses.** In "simulated" mode the UI says "Email sent" / "Meeting scheduled" although nothing left Pitsch. |
| B3 | P1 | Email + attachments are not saved in one transaction: an oversized second attachment returns 400 after the email and first attachment were already persisted (orphans, no workflow). |
| B4 | P1 | No deduplication: the same Gmail message posted twice creates two workflows (`gmail_id` not unique). |
| B5 | P1 | `runActionAgent` re-loads the workflow and overwrites `executedActionIds` — concurrent updates can be lost (no optimistic locking). |

### 3.3 Reliability

| # | Sev | Finding |
|---|---|---|
| R1 | P0 | Agent pipelines run on an in-process `FixedThreadPool` with an unbounded queue. A restart/deploy loses in-flight work and leaves workflows stuck in `CLASSIFYING`/`PROCESSING` forever; there is no recovery. |
| R2 | P0 | Per-workflow locking uses an in-memory `ConcurrentHashMap<Long,Object>`: correct only for a single instance, and the map grows forever. |
| R3 | P0 | `ddl-auto=update` everywhere; H2 file database is the default (ephemeral disk on PaaS ⇒ data loss). No migrations. |
| R4 | P1 | Workflow status is a free string; no transition validation — any code path can set any state. |
| R5 | P1 | No external-provider timeouts/circuit breaking beyond the AI client; retries use `Thread.sleep` on worker threads. |
| R6 | P2 | No overall workflow timeout; worst case per agent call is 240 s × 3 attempts. |

### 3.4 Scalability / performance

* **N+1 queries**: `WorkflowViews.summary` loads the email and pitch per row; `GET /api/workflows` is unpaginated.
* `candidatesFor` loads **all** pitches of a user for every classification.
* Lists (`/pitches`, `/emails`, `/workflows`, `/tasks`, `/events`) are unpaginated and unindexed beyond PKs.
* The `workflows` row carries seven large JSON `TEXT` blobs; list queries read all of them.

### 3.5 UX problems

* Dashboard is a generic task manager (task counts, quick "New task"); Pitsch's identity (pitch intelligence) is buried.
* Navbar search only searches tasks. No server-side search/filter/sort for pitches. No pipeline view.
* No signup page in the UI (API exists), no onboarding, no integrations page, no members/roles, no audit view.
* "Simulated" external actions reported as done (see B2). Demo credentials on the login page. `<title>frontend</title>`.
* Errors show messages but no request ID / support reference; sessions silently die after 24 h.

### 3.6 Technical debt

* Duplicated free-mail domain lists (Java + Python); duplicated example JSON in two places.
* `TestController` (`/api/hello`) is dead code. `ai-service/.github/workflows/tests.yml` never runs (nested `.github`).
* Magic strings for statuses, actions, notification types. 850-line `App.css` + 570-line `index.css`.
* README describes planned features alongside implemented ones in a 1,100-line document.

### 3.7 Missing for a market-ready SaaS

Organizations/workspaces, memberships, RBAC; refresh-token sessions; email verification; password reset;
real Gmail/Calendar/Sheets (OAuth, encrypted tokens, sync, revoke); object storage; migrations; durable job
queue; idempotency keys; audit log; usage tracking and quotas; billing foundation; notification preferences
and email notifications; data export/deletion/retention; observability (request IDs, metrics, probes);
Dockerfiles; CI; evaluation datasets.

### 3.8 Deployment risks

No Dockerfiles, no root CI, development-only start scripts, default `FRONTEND_URL`/API URL point at
`localhost`, and production would silently run with H2, demo user, default JWT secret and simulated integrations.

---

## 4. Recommended changes (implemented in this upgrade unless noted)

1. **Secrets & defaults** — delete `.env` from the tree; production profile fails fast on default/short JWT
   secret, missing encryption key, H2 URL, wildcard CORS, demo mode, or missing internal AI token.
2. **Flyway** — V1 baseline mirroring today's schema (so existing databases can be baselined), V2+ for
   tenancy, sessions, integrations, jobs, audit, usage, billing; `ddl-auto=validate`.
3. **Tenancy & RBAC** — `organizations`, `memberships(role)`, `organization_id` on every tenant-owned table,
   backfill one personal workspace per existing user, tenant derived from the authenticated session only,
   permission checks on every endpoint (`@RequiresPermission`).
4. **Auth** — 15-min access JWT (`iss`, `aud`, `sid`, `jti`), rotating refresh tokens in an httpOnly cookie
   with reuse detection, session list/revoke, email verification, password reset, password change, lockout,
   PBKDF2 600k with rehash-on-login.
5. **Approval-authoritative execution** — the backend decides what needs approval; approvals are explicit
   records created only by a user request; side-effects are keyed by backend-owned idempotency keys
   (fixes S5, B1).
6. **Provider abstraction** — `EmailProvider`, `CalendarProvider`, `SpreadsheetProvider`, `StorageProvider`;
   Google implementations over the REST APIs; mock implementations only when `PITSCH_MODE=demo`.
7. **Durable jobs** — PostgreSQL job table with atomic claim, retries with backoff, stale-lock recovery,
   dead-lettering, and startup recovery of stuck workflows (ADR-004 explains why not Redis).
8. **Workflow state machine** — explicit transition table; `@Version` optimistic locking replaces in-memory locks.
9. **Storage** — S3-compatible object storage with signed URLs; magic-byte validation, zip-bomb limits, ClamAV boundary.
10. **AI service** — internal service token, per-agent model + fallback model, cost estimation, input hashing,
    output-token budgets, prompt-injection flagging, URL scheme allowlist, richer brief sections, the
    SUPPORTED/PARTIALLY_SUPPORTED/UNSUPPORTED/CONTRADICTED/NOT_FOUND vocabulary, and an evaluation suite.
11. **Observability** — request IDs in logs and error bodies, `/health/live`, `/health/ready`, Prometheus metrics.
12. **Frontend** — typed API client with in-memory access token + silent refresh, signup/verify/reset,
    onboarding, Inbox, Pitches (server-side search/filter/sort/pagination), Pipeline, Workflow timeline,
    Integrations, Approvals, Settings (workspace, members, security, notifications, privacy, audit, billing).
13. **Delivery** — multi-stage non-root Dockerfiles, `docker-compose.yml`, GitHub Actions (build, lint, tests,
    Docker build, dependency audit, secret scan).

Items intentionally **not** done are listed in `docs/CHANGELOG_PRODUCTION_UPGRADE.md` → "Remaining limitations".
