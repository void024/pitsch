# Data Security

How Pitsch protects data in transit, at rest and in use. Threat model and application controls:
[SECURITY.md](SECURITY.md). User rights and retention: [PRIVACY.md](PRIVACY.md).

## Classification

| Class | Examples | Handling |
|---|---|---|
| **Secret** | OAuth tokens, password hashes, JWT secret, encryption keys, API keys, refresh tokens | Encrypted or hashed at rest; environment/secret store only; never logged, exported or sent to the AI service |
| **Confidential** | Pitch emails, decks, briefs, research, drafts, deal notes | Tenant-scoped; private object storage; sent only to the configured LLM/search providers for processing; covered by retention |
| **Internal** | Usage counters, audit events, workflow metadata, agent metrics | Tenant-scoped; audit events visible to OWNER/ADMIN |
| **Public** | Plan catalogue, health status | — |

## In transit

* Terminate TLS at the platform load balancer; the backend sets HSTS in production and `ProductionConfigValidator`
  requires `https` for `FRONTEND_URL`, `PUBLIC_API_URL` and CORS origins.
* Backend → AI service runs on the private network with `X-Internal-Token`. If it must cross a public network, put
  it behind TLS (the platform's private networking or an internal load balancer).
* Backend → PostgreSQL: use `sslmode=require` (or `verify-full`) in `DB_URL` on managed databases.
* Google, Stripe, S3, LLM and search APIs are called over HTTPS only.

## At rest

| Data | Mechanism |
|---|---|
| Google refresh/access tokens, OAuth PKCE verifiers | AES-256-GCM in the application (`security/TokenCipher`). Ciphertext format `v1:<keyId>:<iv>:<ciphertext+tag>`, random 96-bit IV per value. Keys from `PITSCH_ENCRYPTION_KEYS` (`id:base64key`, comma-separated, first = current) |
| Passwords | PBKDF2-HMAC-SHA256, 600k iterations, 16-byte salt |
| Refresh / verification / reset / invitation tokens | Stored only as SHA-256 hashes; raw values exist only in the cookie or email link |
| Database | Managed PostgreSQL storage encryption (enable it on your provider) + encrypted backups |
| Files | Private S3-compatible bucket, server-side encryption (enable SSE-S3/SSE-KMS or the provider default), random object keys `org/<orgId>/files/<uuid>.<ext>`, SHA-256 stored for integrity |

### Key rotation

1. Generate a new key: `echo "k2:$(openssl rand -base64 32)"`.
2. Deploy with `PITSCH_ENCRYPTION_KEYS=k2:<new>,k1:<old>` — new tokens use `k2`, old ones still decrypt.
3. Each connection is moved to the current key the next time its access token is refreshed (roughly hourly while
   Gmail sync or Calendar use is active). When no row still starts with `v1:k1:` (check
   `integration_connections.refresh_token_enc`), remove `k1`; connections that were never refreshed must reconnect.

`JWT_SECRET` rotation signs everyone out (access tokens are short-lived; refresh tokens are opaque and unaffected,
so sessions resume on the next refresh). `AI_SERVICE_TOKEN` rotation: the AI service accepts exactly one token, so
set the new value on both services and deploy them together; a workflow step that fails during the switch shows
a Retry action in the UI.

## In use

* **Least privilege.** The AI service receives only the text it needs for one agent call (email text, extracted deck
  text, prior agent outputs) and holds no credentials beyond its LLM/search keys. The backend database role needs
  DML on the schema plus DDL for Flyway (use a separate migration role if your platform supports it).
* **Download links** are presigned GET URLs valid for `SIGNED_URL_MINUTES` (10), issued only after the tenant and
  permission checks; the bucket has no public access.
* **Exports** are streamed to the requesting user and not stored server-side.
* **Logs** carry ids, not content. The AI service's JSON logs redact strings that look like API keys or bearer
  tokens. Do not enable DEBUG logging in production (it is off by default in production mode).
* **Agent payload retention.** Full agent inputs/outputs are kept for `AGENT_IO_RETENTION_DAYS` for debugging and
  evaluation, then nulled; aggregate metrics (tokens, latency, cost, status) remain.

## Tenant isolation

Logical isolation in a shared database: every tenant row has `organization_id`, every query filters by the
session's workspace, and object keys are prefixed by workspace id. Cross-tenant access is covered by automated tests
(`TenantIsolationTests`). Customers that require physical isolation can run a dedicated deployment (all three
services and the database) — nothing in the code assumes a shared instance.

## Backups and recovery

* Enable point-in-time recovery on PostgreSQL (target RPO ≤ 5 min) and versioning or replication on the bucket.
* Backups contain confidential data and secrets in encrypted form; the encryption keys are **not** in the database —
  store them in the secret manager and include them in the disaster-recovery runbook
  ([OPERATIONS.md](OPERATIONS.md)). Losing `PITSCH_ENCRYPTION_KEYS` means users must reconnect Google.
* Deleted workspaces disappear from backups when the backup retention window passes; state that window in your
  privacy notice.

## Third parties

| Provider | Data sent | Purpose |
|---|---|---|
| LLM provider (Gemini or OpenAI-compatible) | Email/deck text, research snippets, prior agent outputs | Agent reasoning |
| Tavily (search) | Company names, claims, search queries | Research evidence |
| Google APIs | OAuth tokens; message, event and sheet operations for the connected account | Integrations |
| Object storage provider | Attachment bytes | Storage |
| SMTP provider | Recipient email, notification text (no deck content) | Transactional email |
| Stripe (optional) | Workspace id, owner email, plan | Billing |
