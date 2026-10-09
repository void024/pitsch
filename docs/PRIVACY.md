# Privacy

Pitsch processes confidential investment data on behalf of the workspace that uses it. In data-protection terms
the customer workspace is the **controller** of pitch data (emails, decks, founders' personal data, notes) and the
operator of a Pitsch deployment is its **processor**. This document describes what the software does; the operator
remains responsible for its own privacy notice, DPA, sub-processor list and lawful basis.

## What is collected

| Data | Why | Where |
|---|---|---|
| Account: name, email, password hash, timezone, preferences | Sign-in, notifications | `users`, `user_settings` |
| Sessions: IP address, user agent, timestamps | Device list, security, audit | `sessions`, `audit_events` |
| Workspace: name, members, roles, working hours, policies | Collaboration, scheduling | `organizations`, `memberships` |
| Pitch emails and attachments (from manual import or the connected Gmail inbox) | The product | `emails`, `email_attachments`, object storage |
| Derived data: classifications, extracted deck facts, research evidence, verification, briefs, drafts, slots | The product | `workflows`, `agent_executions`, `pitches` |
| Google OAuth tokens (encrypted), connected account email, granted scopes | Gmail / Calendar / Sheets | `integration_connections` |
| Usage counters and AI cost estimates | Quotas and billing | `usage_records`, `agent_executions` |
| Billing references (Stripe customer/subscription ids — never card data) | Subscriptions | `subscriptions` |

**Gmail scope.** With Gmail connected, Pitsch reads messages matching `GMAIL_INGEST_QUERY` (default: inbox without
promotions/social/forums) and stores only messages it ingests. It does not read other mailboxes, and it sends email
only when a member approves a specific draft. Use of data obtained through Google APIs follows the
[Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy),
including its Limited Use requirements: data is used only to provide the user-facing features, not for advertising,
and not used to train models.

## Sub-processing by AI and search providers

To analyse a pitch, the AI service sends the email text, extracted deck text and research context to the
configured **LLM provider** (`LLM_PROVIDER`, e.g. Google Gemini) and company/claim search queries to the **search
provider** (Tavily). Operators must list these as sub-processors and choose provider terms that do not use API
inputs for training (check the provider's current API data-use terms). No Google OAuth tokens, passwords or
workspace member data are sent to either.

## Retention

| Data | Default | Setting |
|---|---|---|
| Agent inputs/outputs stored for debugging (`agent_executions.input_json/output_json`) | 90 days, then removed (metrics stay) | `AGENT_IO_RETENTION_DAYS` |
| Email bodies and intermediate workflow outputs | Kept while the workspace exists | Workspace **Data retention (days)** in Settings → Workspace: bodies are redacted and intermediate outputs removed after N days; pitches, briefs and metadata remain |
| Audit events | 730 days | `AUDIT_RETENTION_DAYS` |
| Expired sessions / idempotency records | Purged automatically (30 days / 24 hours) | — |
| Database backups | Operator's backup policy | Make backup retention ≤ your promised deletion window |

The purge runs daily as the `RETENTION_PURGE` job and is itself audited.

## Rights and how to exercise them

| Right | In the product | API |
|---|---|---|
| Access / portability (account) | Settings → Privacy → Export my data (JSON: profile, settings, memberships, sessions, connected integrations, notifications, own audit trail) | `GET /api/v1/me/export` |
| Access / portability (workspace) | Settings → Privacy → Export workspace (ZIP: `workspace.json` with members, pitches, emails, workflows, drafts, events, tasks, file index, plus the original files) — `DATA_EXPORT` permission | `GET /api/v1/workspace/export` |
| Rectification | Profile, pitch and task editing | `PUT /api/v1/users/me`, `PATCH /api/v1/pitches/{id}` |
| Erasure (single records) | Delete email (with its attachments and analysis), pitch, task or event; disconnect integration | `DELETE /api/v1/emails/{id}`, `/pitches/{id}`, `/tasks/{id}`, `/events/{id}` |
| Erasure (account) | Settings → Privacy → Delete account (password + typing DELETE) | `POST /api/v1/me/delete` |
| Erasure (workspace) | Settings → Privacy → Delete workspace (owner, password + workspace name) | `POST /api/v1/workspace/delete` |
| Withdraw Google access | Integrations → Disconnect (revokes the Google grant when nothing else uses it) | `DELETE /api/v1/integrations/{name}` |

**Account deletion** is refused while the user is the only owner of a workspace that has other members (transfer
ownership first). Workspaces where the user is the only member are deleted with the account. Content the user
created in shared workspaces belongs to that workspace and remains.

**Workspace deletion** ends access immediately (memberships and invitations removed, Google grants revoked) and a
background job deletes every stored object and every workspace row. Audit events contain identifiers, actions,
IP addresses and counts — not email or document content — and are kept for the audit retention period as a
security record, after which they are purged.

**Founders' requests.** Founders whose emails were processed are data subjects of the *workspace*, which handles
their requests: **delete the email** (workflow → Email tab → *Delete this email*, `DELETE /api/v1/emails/{id}`) to
erase the message, its attachments and stored objects, and its workflows with their agent outputs, drafts and
approvals; then **delete the pitch** (`DELETE /api/v1/pitches/{id}`) to remove the company record. Meetings that
were actually created stay in the calendar and are unlinked. For access requests, use the workspace export.

## Data minimisation in logs

Application logs contain request ids, user/workspace ids, routes, timings and error codes. They never contain
passwords, tokens, API keys, email bodies or document text. See [DATA_SECURITY.md](DATA_SECURITY.md).
