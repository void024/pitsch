# Deployment

## Production topology

```
            ┌────────────── your domain: app.example.com ──────────────┐
 Browser ──►│ frontend container (nginx)  /  → SPA, /api → backend     │
            └──────────────────────────────┬───────────────────────────┘
                                           │ private network
              ┌────────────────────────────▼─────────────┐      ┌───────────────────────┐
              │ backend container(s)  :8080 (+ :8081 mgmt)│────►│ ai-service container  │
              └───┬──────────────┬───────────────┬────────┘      │ :8000, private only   │
                  │              │               │               └───────────────────────┘
          Managed PostgreSQL   S3 / R2 bucket   SMTP relay, Google APIs, Stripe (optional)
```

* **Same site is the recommended setup**: the frontend's nginx proxies `/api` to the backend, so the refresh cookie is
  first-party (`SameSite=Lax`) and no CORS is needed. Point Google's redirect URI and the Stripe/Gmail webhooks at
  `https://app.example.com/api/v1/...`.
* Split origins (SPA on a CDN such as Vercel, API on `api.example.com`) also work: build the SPA with
  `VITE_API_BASE_URL=https://api.example.com/api`, set backend `CORS_ALLOWED_ORIGINS=https://app.example.com`,
  `COOKIE_SAME_SITE=None` and serve both over HTTPS. Mirror the nginx security headers on the CDN.
* The AI service must not be publicly reachable (private service / internal network). It also refuses requests
  without the shared `AI_SERVICE_TOKEN`.
* No Redis is required (job queue in PostgreSQL, ADR-004). Run **≥ 2 backend instances** for availability; all
  instances run job workers by default (`JOBS_ENABLED=true`). Sessions, locks and quotas live in the database, so
  instances are stateless.

Images: `backend/Dockerfile` (Temurin 21 JRE, non-root uid 10001, layered jar), `ai-service/Dockerfile`
(Python 3.12 slim, non-root), `frontend/Dockerfile` (Node build → `nginx-unprivileged`, port 8080). CI builds all
three on every change and `.github/workflows/release.yml` pushes them to GHCR after CI passes on `main`.

## Required configuration (production)

The backend fails fast at startup if any of these is missing or unsafe (`ProductionConfigValidator`).

| Variable | Service | Value |
|---|---|---|
| `PITSCH_MODE` | backend, ai-service | `production` (the default when unset) |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | backend | `jdbc:postgresql://host:5432/pitsch?sslmode=require` |
| `JWT_SECRET` | backend | `openssl rand -base64 48` |
| `PITSCH_ENCRYPTION_KEYS` | backend | `k1:$(openssl rand -base64 32)` |
| `AI_SERVICE_URL`, `AI_SERVICE_TOKEN` | backend (+ token on ai-service) | internal URL; `openssl rand -base64 48` |
| `FRONTEND_URL`, `PUBLIC_API_URL` | backend | `https://app.example.com` (both, for same-site) |
| `CORS_ALLOWED_ORIGINS` | backend | `https://app.example.com` |
| `MAIL_PROVIDER=smtp`, `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `MAIL_FROM` | backend | your SMTP relay |
| `STORAGE_PROVIDER=s3`, `S3_BUCKET`, `S3_REGION`, `S3_ENDPOINT`, `S3_ACCESS_KEY_ID`, `S3_SECRET_ACCESS_KEY` | backend | private bucket |
| `LLM_PROVIDER`, `LLM_MODEL`, `LLM_API_KEY` | ai-service | e.g. `gemini`, `gemini-flash-latest` |
| `TAVILY_API_KEY` | ai-service | strongly recommended (no key = no web evidence) |
| `BACKEND_URL` | frontend | `http://backend:8080` (internal) |

Optional: Google (`GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GOOGLE_REDIRECT_URI`, Pub/Sub vars — see
[INTEGRATIONS.md](INTEGRATIONS.md)), Stripe (`BILLING_PROVIDER=stripe` + keys), `MALWARE_SCANNER=clamav`,
`LLM_FALLBACK_MODEL`, per-agent models, `LLM_PRICES`, rate limits, retention days. Full lists:
`backend/.env.example`, `ai-service/.env.example`, `frontend/.env.example`.

Never set in production: `DEMO_PASSWORD`, `DDL_AUTO` other than `validate`, `API_DOCS_ENABLED=true` on a public
host, `COOKIE_SECURE=false`, `REQUIRE_EMAIL_VERIFICATION=false`.

## Step by step

1. **Provision** PostgreSQL 16 (PITR on, `pg_trgm` available — Flyway enables it), a private bucket, an SMTP relay,
   and secrets in your platform's secret store.
2. **Google** (if used): follow [INTEGRATIONS.md](INTEGRATIONS.md#google-cloud-setup).
3. **Deploy the AI service** (private). Check `GET /health/ready` from inside the network.
4. **Deploy the backend.** On first start Flyway creates the schema (or baselines and upgrades an existing
   pre-upgrade database — see Migration below). Probes: liveness `GET /health/live`, readiness `GET /health/ready`
   on `:8080`; Prometheus on `:8081/actuator/prometheus` (keep `:8081` private).
5. **Deploy the frontend** with `BACKEND_URL` pointing at the backend service.
6. **Smoke test:** sign up (the first user creates the first workspace and becomes OWNER), verify the email, import
   a sample pitch (Inbox → Import email), watch the agents run, connect Google, approve a test reply to yourself.
7. **Lock down:** confirm `/api/docs` is 404 publicly, management port not exposed, AI service not reachable from the
   internet, bucket not public.

### Render example

Create a *Private Service* for `ai-service` (Docker, `ai-service/`), a *Web Service* for `backend` (Docker,
`backend/`, health check path `/health/ready`), a *Web Service* for `frontend` (Docker, `frontend/`,
`BACKEND_URL=http://<backend-internal-host>:8080`), and a managed PostgreSQL. Put the custom domain on the frontend.
Fly.io, Railway, ECS/Fargate, Cloud Run and Kubernetes work the same way: three containers, one database.

### Scaling notes

* Backend: CPU-light, I/O-bound; 1 vCPU / 1 GB per instance is enough for small teams. `JOB_THREADS` (4) bounds
  concurrent agent pipelines per instance; DB pool `DB_POOL_SIZE` (10) per instance — keep `instances × pool` below
  the database connection limit.
* AI service: stateless; scale on latency. `WEB_CONCURRENCY` uvicorn workers (2) per container; LLM calls dominate.
* If you dedicate instances to the API, set `JOBS_ENABLED=false` on those and run worker instances with it on.

## Migration from the pre-upgrade version

1. **Back up** the existing database (H2 file `backend/data/pitsch*.db` or PostgreSQL dump).
2. If you were on H2, move to PostgreSQL first: start the **old** version against an empty PostgreSQL with
   `ddl-auto=update` to create its tables, copy the data (e.g. H2 `SCRIPT` → adapt → `psql`, or a small ETL), then stop.
3. Start the new backend against that database. Flyway baselines the existing schema as **V1** and applies:
   * **V2** — organizations/memberships (one personal workspace per existing user, user becomes OWNER;
     all existing pitches, emails, workflows, tasks, events and notifications are moved into it), sessions and
     tokens, integrations, approvals, external operations, jobs, idempotency, notifications preferences, plans and
     subscriptions (FREE), usage, audit events, stored files, indexes and constraints. Existing users are marked
     email-verified so they can sign in.
   * **V3** — PostgreSQL trigram search indexes and the audit immutability trigger.
4. Existing users sign in with their old passwords (hashes are kept and upgraded on next login). Old JWTs are
   invalid — everyone signs in once.
5. Old attachments stored as base64 in the database keep working (served via the API); new ones go to object
   storage.
6. Revoke the Gemini key that was distributed in the original ZIP's `ai-service/.env` and use a new one.

## Rollback

Application rollback is safe while no new migration has run. Migrations are forward-only; to roll back across a
migration, restore the pre-deploy database snapshot (take one before every release that adds a migration).
