# Integrations

Pitsch integrates with Gmail, Google Calendar and Google Sheets through provider interfaces
(`integration/provider/EmailProvider`, `CalendarProvider`, `SpreadsheetProvider`). Production uses the Google
implementations (`integration/google/*`); `PITSCH_MODE=demo` swaps in clearly labelled mocks
(`integration/mock/*`). Object storage (`files/StorageProvider`) and billing (`billing/StripeClient`) follow the same
pattern.

## Google Cloud setup

1. Create a Google Cloud project. Enable **Gmail API**, **Google Calendar API**, **Google Sheets API** (and
   **Cloud Pub/Sub API** for push).
2. OAuth consent screen: *External* (or *Internal* for a Workspace-only deployment). Add the scopes below, your
   privacy policy and terms URLs, and an authorized domain.
3. Credentials → OAuth client ID → *Web application*. Authorized redirect URI:
   `https://<your-app>/api/v1/integrations/google/callback` (same-site deployment) — must equal
   `GOOGLE_REDIRECT_URI`. Set `GOOGLE_CLIENT_ID` and `GOOGLE_CLIENT_SECRET` on the backend.
4. Optional sign-in with Google: `GOOGLE_LOGIN_ENABLED=true` on the backend and `VITE_GOOGLE_LOGIN=true` on the
   frontend build (uses `openid email profile` only).
5. **Verification.** `gmail.modify` is a *restricted* scope and the Calendar/Sheets scopes are *sensitive*. Until
   Google verifies the app, only test users you list can connect (max 100) and they see an "unverified app"
   warning. Public launch requires OAuth verification and, for Gmail, the annual third-party security assessment
   (CASA). Plan for this before onboarding customers.

### Scopes

| Integration | Scopes | Used for |
|---|---|---|
| (always) | `openid`, `email` | Identify the connected Google account |
| Gmail | `https://www.googleapis.com/auth/gmail.modify` | Read incoming messages and attachments, apply `Pitsch/…` labels, send approved replies in-thread |
| Calendar | `calendar.events`, `calendar.freebusy`, `calendar.calendarlist.readonly` | Free/busy checks, create / update / cancel approved meetings, choose a calendar |
| Sheets | `spreadsheets` | Read headers, create and update pipeline rows |

Each integration is connected separately (incremental authorization with `include_granted_scopes`), so a user can
connect Calendar without granting Gmail. Authorization Code flow with **PKCE**; the `state` is a single-use random
value stored (encrypted verifier, user, workspace, return path) for 10 minutes. Access and refresh tokens are
encrypted with AES-256-GCM; access tokens are refreshed shortly before expiry under a per-connection lock.

### Connection states

`CONNECTED` · `NEEDS_RECONNECT` (Google returned `invalid_grant` — token revoked/expired; the user is notified and
sees **Reconnect**) · `ERROR` (last sync failed; error shown, retried) · `REVOKED` (disconnected). The Integrations
page shows each integration's status, connected account, granted scopes, last sync time and last error.

**Disconnect** turns that integration off for the connection (Gmail also stops its push watch), so syncs and
actions no longer use it; when no integration on the connection remains, Pitsch revokes the Google grant
(`oauth2.googleapis.com/revoke`) and deletes the tokens. Google keeps already-granted scopes on a partially
disconnected grant until it is fully revoked.
Account and workspace deletion also revoke grants.

## Gmail

**Ingestion.** Two triggers, one pipeline:

* *Push (recommended):* create a Pub/Sub topic, grant `gmail-api-push@system.gserviceaccount.com` the
  *Publisher* role on it, and create a **push subscription** to `https://<api>/api/v1/webhooks/gmail` with
  *authentication enabled* (a service account; audience = `GOOGLE_PUBSUB_AUDIENCE`). Set `GOOGLE_PUBSUB_TOPIC`,
  `GOOGLE_PUBSUB_AUDIENCE`, `GOOGLE_PUBSUB_SERVICE_ACCOUNT`. Pitsch calls `users.watch` on connect and renews
  watches daily (they expire after 7 days). The webhook verifies Google's OIDC token and only enqueues a sync.
* *Polling fallback:* every `GMAIL_SYNC_INTERVAL_MINUTES` (default 10) using the History API from the stored
  `historyId` cursor — cheap incremental calls, not full listing. Push makes polling a safety net.

A sync lists new message ids matching `GMAIL_INGEST_QUERY` and enqueues one `GMAIL_INGEST_MESSAGE` job per message.
Each message is ingested **once**: the job dedupe key and the unique `(organization_id, dedupe_key = gmail:<id>)`
on `emails` make repeated pushes, overlapping polls and retries no-ops. Thread id, RFC 5322 Message-ID,
In-Reply-To, sender name/address, received time, labels and attachments (validated like uploads) are stored.

**Sending.** Only through an approved `SEND_EMAIL` approval. The message is sent in the original thread
(`threadId`, `In-Reply-To`, `References`) with a deterministic `Message-ID` derived from the approval's idempotency
key; before re-sending after an uncertain failure, Pitsch searches the mailbox for that Message-ID, so a retry can
never send twice. Status is `SENT` only after Gmail returns the message id.

**Labels.** The Action Agent may suggest labels; only labels under `GMAIL_LABEL_PREFIX` (default `Pitsch/`) are
created/applied, per the workspace policy (`AUTO`, `APPROVAL`, `OFF`).

## Google Calendar

* The Calendar Agent ranks candidate slots; the backend **first** removes any slot outside the workspace's working
  hours/days or overlapping the user's free/busy (and existing Pitsch meetings), then shows them.
* The user picks a slot → `CREATE_MEETING` approval → execution creates the event with attendees (the founder),
  optional Google Meet link (`conferenceData`), reminders and `sendUpdates=all`. The event id is derived from the
  idempotency key, so a retried create finds the existing event instead of duplicating it.
* The external event id, calendar id, HTML link and conference link are stored with `syncStatus`.
* Every 30 minutes `CALENDAR_SYNC` checks upcoming Pitsch-created events: if someone moved or deleted the event in
  Google Calendar, Pitsch updates its record, audits `MEETING_CHANGED_EXTERNALLY` and notifies the owner. Pitsch
  never re-creates a meeting that someone deleted.
* Cancelling from Pitsch is a `CANCEL_MEETING` approval and cancels the Google event (attendees are notified).

## Google Sheets (pipeline)

Workspace admins (`INTEGRATION_MANAGE`) configure the pipeline sheet in **Integrations → Pipeline sheet**:

1. Paste the spreadsheet URL or id → Pitsch reads the worksheet list and the header row (`headerRow`, default 1).
2. Map Pitsch fields to columns. Available fields: `pitchId, companyName, founderName, founderEmail, website,
   sector, stage, dealStage, status, owner, amountRequested, oneLiner, claimsSupported, claimsContradicted,
   claimsUnresolved, riskLevel, briefUrl, lastActivityAt, createdAt`. Unmapped columns are never touched.
3. Choose the policy (`AUTO` sync on changes, `APPROVAL` per change, `OFF`).

The `pitchId` field must be mapped: it is the row key. Each sync looks the pitch up in that column (so sorting or
inserting rows in the sheet is safe), updates only the mapped cells of the existing row, or appends one row if the
pitch is not there yet — a pitch never gets two rows. The row is also recorded in `pipeline_sheet_rows`, and each
write runs through the external-operation ledger, so a retried job is a no-op. If a mapped column was renamed or
removed in the sheet, that field is skipped rather than written to the wrong column. Values are written `RAW`
(never evaluated as formulas) and values starting with `=`, `+` or `@` are additionally prefixed with `'`.
"Sync all" back-fills existing pitches.

## Object storage

S3-compatible (`STORAGE_PROVIDER=s3`): AWS S3 (leave `S3_ENDPOINT` empty, use IAM credentials or keys), Cloudflare
R2 (`S3_ENDPOINT=https://<account>.r2.cloudflarestorage.com`, `S3_REGION=auto`), MinIO (`S3_PATH_STYLE=true`), etc.
The bucket must be private. Downloads use presigned GET URLs (`SIGNED_URL_MINUTES`); `S3_PUBLIC_ENDPOINT` sets a
different host for those URLs when the backend reaches the store on an internal address (as in docker compose).

## Transactional email

`MAIL_PROVIDER=smtp` (any SMTP relay: SES, Postmark, SendGrid, Mailgun) for verification, password reset,
invitations and notification emails. `log` prints them to the backend log (development/demo only; refused in
production).

## Stripe (optional)

`BILLING_PROVIDER=stripe`, `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `STRIPE_PRICE_IDS=PRO=price_…,TEAM=price_…`.
Create a webhook endpoint `https://<api>/api/v1/webhooks/stripe` for `checkout.session.completed`,
`customer.subscription.created|updated|deleted`. Checkout and the billing portal are opened from Settings → Billing
(owner only). Plan and status change only when a signed webhook arrives.

## Malware scanning (optional)

`MALWARE_SCANNER=clamav` with `CLAMAV_HOST`/`CLAMAV_PORT` streams every upload to clamd (INSTREAM) before it is
stored; infected or unscannable files are rejected (`FILE_REJECTED`). `docker compose --profile scan up` starts a
local clamd.
