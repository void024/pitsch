# Pitsch

**AI-powered, human-in-the-loop pitch management and investment workflow automation.**

![React](https://img.shields.io/badge/Frontend-React%2019-61DAFB?logo=react&logoColor=white)
![TypeScript](https://img.shields.io/badge/Language-TypeScript-3178C6?logo=typescript&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Backend-Spring%20Boot%203.5-6DB33F?logo=springboot&logoColor=white)
![Java](https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white)
![FastAPI](https://img.shields.io/badge/AI-FastAPI-009688?logo=fastapi&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/Database-PostgreSQL%2016-4169E1?logo=postgresql&logoColor=white)

Investors, VC teams, accelerators and corporate venture teams receive pitches by email. Reading each one, digging
through decks, checking the founders' claims and writing back takes hours, and the decks that deserve attention get
lost among the ones that don't. Pitsch reads the inbox, recognises pitches and follow-ups, extracts the deck,
researches the company on the web, checks each claim against the evidence and writes a structured investment brief —
then **waits for a human** before it replies, books a meeting or updates the pipeline.

```
Pitch email ─► classify (new / follow-up / not a pitch) ─► you decide ─► deck analysis ─► web research
   ─► claim verification ─► investment brief ─► you decide ─► draft reply / meeting slots / pipeline update
   ─► you approve ─► Pitsch executes once (Gmail, Calendar, Sheets) ─► audit trail + notifications
```

Pitsch never makes an invest / pass call. The brief presents evidence, contradictions, risks, missing information
and the questions to ask; the decision is the investor's.

---

## Features

| Area | What it does |
|---|---|
| **Workspaces & roles** | Organizations with members, invitations and roles (Owner, Admin, Investor, Analyst, Member); permissions enforced on the server; strict tenant isolation |
| **Accounts** | Email/password with verification, password reset/change, session & device management, lockout, optional Google sign-in |
| **Inbox** | Gmail ingestion (Pub/Sub push + incremental polling) or manual import with attachments; deduplicated by message id |
| **Classification** | New pitch / follow-up / update / not a pitch, follow-ups linked to existing pitches (thread, founder, domain, company), confidence and review routing, prompt-injection flagging |
| **Research pipeline** | Document → Research → Verification → Analysis agents with real-time agent states, tokens, cost and latency per step; resumable on failure |
| **Investment brief** | Summary, company, founders, problem, solution, product, traction, business model, market, competition, fundraising, claims & evidence (Supported / Partially supported / Unsupported / Contradicted / Not found), risks, opportunities, missing information, questions, sources with dates, evidence-support score; Markdown download |
| **Actions with approval** | Reply drafts you edit and approve; meeting slots checked against free/busy and working hours; Google Calendar event with Meet link; Gmail labels and pipeline-sheet updates per workspace policy; every external action executed exactly once |
| **Deal flow** | Pitches list with backend search, filters (stage, status, sector, owner, risk, confidence, follow-up, dates), sorting and pagination; pipeline board; pitch timeline; tasks and calendar |
| **Notifications** | In-app and email, per-type preferences |
| **Audit & privacy** | Immutable audit log, workspace export (ZIP) and account export, email / account / workspace deletion, retention settings, Google access revocation |
| **SaaS foundation** | Plans and usage quotas (workflows, pitches, tokens, research queries, document pages, storage, members), optional Stripe checkout/portal/webhooks |
| **Operations** | Health/readiness probes, Prometheus metrics, JSON logs with request ids, PostgreSQL job queue with retries and dead-lettering, Docker images, CI/CD |

## Architecture

```
Browser ─► frontend (nginx + React SPA) ──/api──► backend (Spring Boot)  ──► PostgreSQL (state, jobs, audit)
                                                     │  ▲                 ──► S3-compatible storage (decks)
                                                     │  └─ Gmail push     ──► Gmail · Calendar · Sheets (OAuth)
                                                     └──X-Internal-Token──► ai-service (FastAPI, 8 agents) ──► LLM · Tavily
```

* **frontend/** — React 19, TypeScript, Vite, React Router; typed fetch client; no tokens in browser storage.
* **backend/** — the only component with credentials: auth, tenancy, RBAC, workflow state machine, approvals, job
  queue, Google providers, storage, quotas, audit, privacy. Flyway-managed PostgreSQL schema.
* **ai-service/** — eight stateless agents (Email Classifier, Document, Research, Verification, Analysis, Calendar,
  Email Response, Action) with schema-validated outputs, provider-agnostic LLM client with fallback, cost tracking
  and prompt-injection defences. It has no access to Google, the database or email.

Details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) · [docs/AI_ARCHITECTURE.md](docs/AI_ARCHITECTURE.md) ·
decisions in [docs/ARCHITECTURAL_DECISIONS.md](docs/ARCHITECTURAL_DECISIONS.md).

## Security in brief

Default-deny authorization with per-permission checks; workspace derived from the session, never from the client;
15-minute access tokens in memory, rotating httpOnly refresh cookie with reuse detection; PBKDF2 (600k) passwords;
AES-256-GCM encrypted OAuth tokens; CSRF guard, CORS allowlist, CSP and security headers; validated uploads (magic
bytes, size, zip-bomb limits, optional ClamAV); rate limits and lockout; uniform error responses with request ids;
production refuses to start with unsafe configuration. AI: untrusted content fenced as data, injection detection,
schema validation, no model capabilities, human approval for every email and calendar change.
See [docs/SECURITY.md](docs/SECURITY.md), [docs/DATA_SECURITY.md](docs/DATA_SECURITY.md), [docs/PRIVACY.md](docs/PRIVACY.md).

## Quick start

### Option A — Docker (PostgreSQL + MinIO, closest to production)

```bash
./scripts/gen-dev-env.sh          # writes .env with random local secrets
#   then put your LLM key in .env: LLM_API_KEY=...  (Gemini: https://aistudio.google.com/apikey)
#   optional: TAVILY_API_KEY=... for web research (https://tavily.com)
docker compose up --build
```

Open **http://localhost:3000**. The default `PITSCH_MODE=demo` seeds `demo@pitsch.app` (password
`pitsch-demo-2026`, or `DEMO_PASSWORD`) and uses **mock** Gmail/Calendar/Sheets — every mock action is labelled
"demo" and nothing leaves your machine. The agents are real and call your LLM. Set `PITSCH_MODE=development` and
the Google variables in `.env` to connect real Google accounts.

### Option B — without Docker

Requirements: Java 21, Node 20.19+, Python 3.11+.

```bash
cp ai-service/.env.example ai-service/.env    # add LLM_PROVIDER / LLM_MODEL / LLM_API_KEY (+ TAVILY_API_KEY)
./start-dev.sh                                # Windows: start-dev.bat
```

Open **http://localhost:5173**. This uses an H2 file database and local file storage; emails (verification,
password reset) are printed in the backend log.

### Try the journey

Sign in → **Inbox → Import email** (paste a pitch, attach a PDF/PPTX deck) → watch the classifier → **Handle pitch** →
watch Document, Research, Verification and Analysis run → read the brief and its evidence → **Plan meeting**, pick a
slot and approve → **Draft reply**, edit and approve-and-send → review **Settings → Audit log**.

## Configuration

Each service has a documented `.env.example`: [backend](backend/.env.example) ·
[ai-service](ai-service/.env.example) · [frontend](frontend/.env.example) · [docker compose](.env.example).
`PITSCH_MODE` selects `production` (default; strict), `development` or `demo`. Production requires PostgreSQL,
`JWT_SECRET`, `PITSCH_ENCRYPTION_KEYS`, `AI_SERVICE_TOKEN`, https URLs, SMTP and S3 — the full list is in
[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md#required-configuration-production).

## Testing

```bash
cd frontend && npm run lint && npm run build && npm test && npm run e2e
cd backend && ./mvnw verify                      # add TEST_DB_URL=jdbc:postgresql://… TEST_DDL_AUTO=validate for PostgreSQL
cd ai-service && python -m pytest -q && python -m evals.run_evals
```

CI (`.github/workflows/ci.yml`) runs all of the above (backend on both H2 and PostgreSQL 16), plus secret scanning,
dependency audits, Trivy, CodeQL and Docker builds. No test needs real Google, LLM, search or Stripe credentials.
Live AI quality evaluation (accuracy, false-SUPPORTED rate, latency, cost): `python -m evals.run_evals --suite live`
— see [ai-service/evals/README.md](ai-service/evals/README.md).

## Deployment

Three containers (frontend, backend, ai-service) + managed PostgreSQL + S3-compatible storage + SMTP; Google Cloud
project for Gmail/Calendar/Sheets; optional Stripe. Images are built and pushed by the release workflow after CI
passes. Step-by-step guide, migration from the previous version and rollback: [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md).
Runbook: [docs/OPERATIONS.md](docs/OPERATIONS.md). Integrations setup: [docs/INTEGRATIONS.md](docs/INTEGRATIONS.md).
API reference: [docs/API.md](docs/API.md) (OpenAPI at `/api/v1/openapi` when enabled).

## Limitations

* **Google verification.** Gmail's `gmail.modify` scope is restricted: before public launch the Google Cloud app
  needs OAuth verification and a CASA security assessment. Until then only listed test users can connect Gmail.
* **Rate limits are per instance** (in memory); lockout and quotas are global. Use an edge rate limiter for
  internet-scale abuse (ADR-007).
* **Malware scanning** is a boundary with a ClamAV implementation, off by default.
* **AI quality depends on the model and search provider.** The live eval datasets are synthetic and small; grow
  them with consented real data and calibrate review thresholds per deployment. Scanned (image-only) decks are not
  OCR'd.
* **Follow-up matching** uses deterministic candidates plus the classifier's judgement, not embeddings (ADR-015).
* **Billing:** Stripe integration covers checkout, portal and subscription webhooks; invoicing, tax and
  proration rules are Stripe's defaults. Plan limits are database rows.
* **Single region, logical multi-tenancy** in one database; dedicated deployments are possible but not automated.
* **Push notifications** (mobile/web push) are not implemented; in-app and email are.

## Roadmap

OCR for scanned decks · second search provider for cross-checking · embeddings-assisted follow-up matching ·
Outlook / Microsoft 365 provider (the provider interfaces are ready) · CRM sync (Affinity, HubSpot) · web push
notifications · SSO (SAML/OIDC) and SCIM for enterprise workspaces · Redis-backed distributed rate limiter.

## Documentation

[Architecture](docs/ARCHITECTURE.md) · [AI architecture](docs/AI_ARCHITECTURE.md) · [Security](docs/SECURITY.md) ·
[Data security](docs/DATA_SECURITY.md) · [Privacy](docs/PRIVACY.md) · [Integrations](docs/INTEGRATIONS.md) ·
[API](docs/API.md) · [Deployment](docs/DEPLOYMENT.md) · [Operations](docs/OPERATIONS.md) ·
[Contributing](docs/CONTRIBUTING.md) · [Decisions](docs/ARCHITECTURAL_DECISIONS.md) ·
[Production audit](docs/PRODUCTION_AUDIT.md) · [Upgrade changelog](docs/CHANGELOG_PRODUCTION_UPGRADE.md)

## Team

| Member | Area | Responsibilities |
|---|---|---|
| Yash Kamble | Frontend | React UI, dashboard, authentication UI, pitch interfaces, workflow UI, notifications, agent progress, investment brief UI, email approval UI, calendar UI, backend API integration |
| Ayush Kumawat | Backend | Spring Boot, REST APIs, authentication, authorization, PostgreSQL, business logic, workflow management, external integrations, agent orchestration integration, security |
| Arav | AI | AI architecture, LLM integration, Agent Orchestrator, Document/Research/Analysis/Action agents, pitch classification, follow-up detection, investment analysis, investment brief generation, AI recommendations |
