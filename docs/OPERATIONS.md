# Operations

Runbook for operating a Pitsch deployment. Architecture: [ARCHITECTURE.md](ARCHITECTURE.md). Deploying:
[DEPLOYMENT.md](DEPLOYMENT.md).

## Health and probes

| Probe | Meaning | Use for |
|---|---|---|
| backend `GET /health/live` | JVM up | liveness / container restart |
| backend `GET /health/ready` | accepting traffic and database reachable | readiness / load balancer |
| backend `GET /api/v1/health` | `status` (db) + `aiService` up/down | status page |
| ai-service `GET /health/live`, `/health/ready` | process up; configuration valid | liveness / readiness |
| frontend `GET /healthz` | nginx up | liveness |

## Logs

* Backend (production): JSON (ECS) on stdout. Every line has `requestId` (MDC) — the same id the user sees in error
  messages ("Request ID: …") and that is forwarded to the AI service as `X-Request-Id`.
* AI service: JSON lines with `request_id`, agent, model, latency, tokens; secrets are redacted.
* Search a user's problem: take the request id from their screenshot → grep both services' logs.
* Content (email bodies, documents, prompts) is not logged by design. For a deep investigation use the workflow's
  agent log (UI: workflow → "Agent log & approvals", or `agent_executions` rows, payloads retained
  `AGENT_IO_RETENTION_DAYS`).

## Metrics (Prometheus, backend `:8081/actuator/prometheus`)

| Metric | Tags | Alert idea |
|---|---|---|
| `http_server_requests_seconds` | uri, status | p95 > 1 s on non-AI routes; 5xx rate > 1 % |
| `pitsch_jobs_queue_depth` | — | > 100 for 10 min (workers stuck or too few) |
| `pitsch_jobs_finished_total` | type, outcome | any `outcome="dead"` increase |
| `pitsch_agent_duration_seconds` | agent, status | `status="failed"` ratio > 20 % over 15 min (LLM/search outage) |
| `pitsch_llm_tokens_total`, `pitsch_llm_cost_usd_total` | agent, kind | daily cost above budget |
| `pitsch_workflow_duration_seconds` | outcome | p95 > 10 min |
| `pitsch_integration_calls_total` | provider, operation, outcome | Google failure ratio > 10 % |
| `hikaricp_connections_pending`, JVM metrics | — | pool exhaustion, memory |

Point an error tracker at stderr/logs (Sentry, Datadog, Grafana Loki) — exceptions are logged once with the request id.

## Background jobs

Jobs live in the `jobs` table. States: `QUEUED → RUNNING → SUCCEEDED | QUEUED (retry with backoff) | DEAD`
(attempts exhausted) | `CANCELLED`. A job whose worker died is re-queued after `JOB_LOCK_TIMEOUT_MINUTES`.

```sql
-- queue overview
select type, status, count(*) from jobs group by 1, 2 order by 1, 2;
-- recent dead jobs with their errors
select id, type, organization_id, workflow_id, attempts, last_error, updated_at
from jobs where status = 'DEAD' order by updated_at desc limit 50;
-- re-run a dead job after fixing the cause
update jobs set status = 'QUEUED', attempts = 0, run_at = now(), last_error = null where id = :id;
```

Scheduled maintenance (deduplicated across instances): Gmail poll every `GMAIL_SYNC_INTERVAL_MINUTES`, Gmail watch
renewal daily, Calendar external-change sync every 30 min, `WORKFLOW_RECOVERY` every 10 min, `RETENTION_PURGE`
daily, idempotency-record purge hourly, finished-job cleanup.

## Common incidents

**LLM provider outage / quota.** Symptoms: agent failures with `LLM_API_ERROR`/`LLM_TIMEOUT`, workflows `FAILED`
with Retry. Actions: check the provider status page; set `LLM_FALLBACK_MODEL` (and, if needed, a fallback provider
via a gateway) and restart the AI service; after recovery, users press **Retry** (completed agents are not re-run).
Nothing is lost: emails and earlier agent outputs are stored.

**Search (Tavily) outage.** Research degrades: the brief is produced from the pitch only, with a visible warning,
and claims end as NOT_FOUND/UNSUPPORTED. Re-run analysis later with Retry if needed.

**Google API errors.** `pitsch_integration_calls_total{outcome="failure"}` rises; connections may flip to `ERROR`
(transient) or `NEEDS_RECONNECT` (`invalid_grant`: revoked or expired grant — only the user can fix it by
reconnecting; they are notified). Approved actions that failed stay `FAILED` on the approval with the error and can
be retried — the idempotency ledger prevents duplicates.

**Gmail push stopped.** Check `integration_connections.gmail_watch_expires_at`; the daily renewal and the
polling fallback keep ingestion going. Verify the Pub/Sub push subscription's authentication audience equals
`GOOGLE_PUBSUB_AUDIENCE`.

**Workflows stuck in PROCESSING.** `WORKFLOW_RECOVERY` marks workflows FAILED after 30 minutes without a live job.
If many appear, look for DEAD `RUN_PIPELINE` jobs and AI service errors.

**Database failover.** The backend retries connections (Hikari); in-flight jobs whose transaction failed are retried
by the queue. Readiness turns `DOWN` while the database is unreachable, so the load balancer drains traffic.

**Account locked.** Lockout clears automatically after `LOCKOUT_MINUTES`. To unlock early:
`update users set failed_login_attempts = 0, locked_until = null where lower(email) = lower(:email);`

**Suspected token leak.** Revoke the user's sessions (Settings → Security, or
`update sessions set revoked_at = now() where user_id = :id`); refresh-token reuse already revokes the session
family automatically and is audited as `REFRESH_TOKEN_REUSE`.

## Secrets rotation

| Secret | Procedure | Impact |
|---|---|---|
| `JWT_SECRET` | replace, rolling restart | access tokens invalid; clients refresh transparently |
| `PITSCH_ENCRYPTION_KEYS` | prepend new key, keep old until no ciphertext uses it ([DATA_SECURITY.md](DATA_SECURITY.md#key-rotation)) | none |
| `AI_SERVICE_TOKEN` | set on both services, deploy together | brief agent failures during the switch (Retry) |
| `GOOGLE_CLIENT_SECRET` | add a new secret in Google Cloud, deploy, delete the old one | none |
| `STRIPE_WEBHOOK_SECRET` | roll in Stripe dashboard, deploy | webhooks fail until deployed (Stripe retries) |
| DB password | rotate in provider, update `DB_PASSWORD`, rolling restart | none with rolling restart |

## Backups and restore

* PostgreSQL PITR (provider feature) + bucket versioning. Test a restore quarterly into a staging environment.
* Restore procedure: restore the database snapshot to a new instance → point a staging backend at it with the same
  `PITSCH_ENCRYPTION_KEYS` → verify → switch production `DB_URL` → rolling restart. Flyway validates the schema
  version on start.

## Data requests

Exports and deletions are self-service (Settings → Privacy) and audited (`DATA_EXPORTED`, `ACCOUNT_DELETED`,
`WORKSPACE_DELETION_REQUESTED`, `WORKSPACE_DELETED`, `EMAIL_DELETED`). Workspace deletion completes asynchronously
(`WORKSPACE_DELETE` job); check `select status, last_error from jobs where type = 'WORKSPACE_DELETE'`.

## Usage and cost

Per-workspace monthly usage is in `usage_records` and on Settings → Billing; per-agent tokens and cost are on the
dashboard. Plan limits are rows in `plans.limits_json` (`-1` = unlimited); change them with SQL or a migration — no
code changes. A workspace's plan without Stripe: `update subscriptions set plan_code = 'TEAM' where organization_id = :id;`
