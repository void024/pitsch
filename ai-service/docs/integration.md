# Backend Integration Guide (Spring Boot ⇄ Pitsch AI)

The backend owns workflow state, persistence, user actions and all Gmail/Calendar/Sheets calls.
The AI service only interprets and structures information. This page says **when to call which
agent, what to send, and what to do with the result.**

Full, real payloads for every step: [`docs/examples/`](examples/) (recorded from the end-to-end test).
Live schemas: `GET /openapi.json` or `/docs` on a running service.

## 1. Rules for every call

| Topic | Rule |
|---|---|
| Envelope | `{ executionId, traceId, input }` → `{ success, agent, data, error, meta }` |
| JSON style | camelCase both ways |
| `executionId` | Deterministic: `<workflowId>:<AGENT>:<stepNumber>`. If you already stored a successful result for an executionId, don't call again (idempotency is the backend's job). |
| `traceId` | Request-scoped, for log correlation |
| Failures | HTTP 200 + `success=false`. Retry only if `error.retryable`. Suggested: 3 attempts, exponential backoff. |
| Invalid input | HTTP 422, `error.code = INVALID_INPUT`, `error.details` lists fields (never values) |
| IDs | `pitchId` etc. may be sent as numbers or strings; they come back as strings (`"42"`). |
| Datetimes | ISO 8601. Send offsets (`+05:30`) where you can; naive times are read in the investor's time zone. |
| Storing results | Store `data` as JSON (e.g. a `jsonb` column per step). Later agents take earlier outputs **unchanged**. |
| `needsHumanReview` | Show `reviewReasons` to the user. It does not mean failure. |
| Timeouts | Classifier/Calendar/Action: ~30 s. Document/Verification/Analysis/Email: ~60 s. Research: ~120 s (many searches). |

Error codes: `INVALID_INPUT`, `INSUFFICIENT_INPUT`, `LLM_TIMEOUT`, `LLM_API_ERROR`,
`MALFORMED_LLM_OUTPUT`, `DOCUMENT_UNREADABLE`, `SEARCH_FAILED`, `RESEARCH_FAILED`, `INTERNAL_ERROR`.

## 2. The workflow, step by step

| When | Backend status | Call | Input built from | Backend then… |
|---|---|---|---|---|
| New email arrives | `CLASSIFYING` | `email-classifier` | Gmail message + recent candidate pitches | `NOT_PITCH` → status `NOT_PITCH` (stop). Else status `AWAITING_USER`, show `recommendedAction`. |
| Pitch / follow-up detected | `AWAITING_USER` | `action` event `PITCH_DETECTED` or `FOLLOW_UP_DETECTED` | classifier output | Execute label + sheet actions |
| User clicks **Handle Pitch** (`COMPLETE_WORKFLOW`) | `PROCESSING` | `action` `PROCESSING_STARTED`, then `document` | email body + attachments | Store document output |
| | `PROCESSING` | `research` | document output (company, founders, sector, claims) | Store research output |
| | `PROCESSING` | `verification` | `document.claims` + `research.evidence` (verbatim) | Store |
| | `PROCESSING` | `analysis` | `{ document, research, verification }` (verbatim) | Store brief, show `markdown` or render `brief` |
| Brief stored | `WAITING_FOR_APPROVAL` | `action` `BRIEF_READY` | pitch summary + `analysis.claimStatusSummary` | Execute labels + sheet |
| User picks **Plan Meeting** | `PROCESSING` | `calendar` | investor prefs + Google free/busy + founder message | Show `slots` (`GET /api/calendar/availability`) |
| User picks **Plan Email Response** (or after slots) | `PROCESSING` | `email-response` | purpose, founder, thread, chosen questions/slots | Show draft (Edit/Send/Cancel) |
| User approves a slot | `PROCESSING` | `action` `MEETING_APPROVED` + `approval` | chosen slot | Execute calendar event, then labels (`dependsOn`) |
| User clicks **Send** | `PROCESSING` | `action` `EMAIL_APPROVED` + `approval` | edited draft | Execute Gmail send |
| Done / Stop | `COMPLETED` / `STOPPED` | `action` `WORKFLOW_COMPLETED` / `WORKFLOW_STOPPED` | — | Execute labels + sheet |

Research and verification are optional: analysis works with only `document` (claims show
`NOT_CHECKED`). If research fails, you can still run analysis and show the partial brief.

## 3. Agent inputs (key fields)

Defaults are shown where they exist. See `docs/examples/NN-*.json` for complete payloads.

### 1. `email-classifier`
`emailId, threadId, inReplyTo, references[], sender{email,name}, to[], cc[], subject, body,
receivedAt, attachments[{filename,mimeType,sizeBytes,readable}], candidatePitches[{pitchId,
companyName, companyDomain, founderEmails[], threadIds[], lastActivityAt}]` (max 20 candidates —
recent pitches with the same thread, sender, or sender domain).

Output: `category, isPitch, isFollowUp, previousPitchId, detectedCompanies[], meetingRequested,
workflowClosed, confidence, recommendedAction (ASK_TO_HANDLE | COMPLETE_WORKFLOW | STOP |
PLAN_MEETING | PLAN_EMAIL_RESPONSE), needsHumanReview, reviewReasons[], warnings[], reason`.

### 2. `document`
`pitchId, companyNameHint, senderEmail, emailSubject, emailBody, documents[{documentId, filename,
mimeType, text | contentBase64}]` — send extracted `text` (use `\f` between pages) **or** the file
as base64 (PDF, PPTX, TXT; max 15 MB, max 10 files).

Output: `company{...}, founders[], fundraise{...}, tractionMetrics[], claims[{claimId, text,
category, quote, sourceRef, quoteVerified}], links[], missingInformation[], documents[]`.

### 3. `research`
`pitchId, companyName, companyDomain, founders[], sector, location, oneLiner, claims[]` (the
document claims, as-is), optional `focusTopics[]`, `maxQueries`.

Output: `sources[], evidence[{evidenceId, sourceId, topic, statement, excerpt, claimIds[],
sourceType COMPANY|EXTERNAL, sourceUrl, publishedAt, retrievedAt, possiblyOutdated}],
competitors[], queriesRun[], gaps[], claimsWithoutEvidence[]`.

### 4. `verification`
`pitchId, companyName, claims[]` (document claims) `, evidence[]` (research evidence).

Output: `results[{claimId, status, independentlyVerified, supportingEvidenceIds[],
contradictingEvidenceIds[], finding, confidence, evidenceOutdated, notes[]}], summary{status: n}`.

### 5. `analysis`
`pitchId, document, research?, verification?` — the stored outputs, unchanged.

Output: `brief{companyOverview[], executiveSummary[], claimsMatrix[], tractionMetrics[], market[],
competition[], competitors[], founders[], fundingHistory[], risks[], openQuestions[], sources[]},
markdown, claimStatusSummary, disclaimer`. Every finding has `citations[]` and `provenance`
(`PITCH | COMPANY | EXTERNAL | AI_INFERENCE`).

### 6. `calendar`
`timezone ("Asia/Kolkata"), searchStart?, searchEnd? (default 7 days), durationMinutes (30),
bufferMinutes (15), minNoticeHours (12), workingHours{start "09:30", end "18:30", days[MON..FRI]},
busy[{start,end}]` (from Google Calendar free/busy) `, maxMeetingsPerDay (6), preferredWindows[{start,end}],
lunch ({13:00-14:00} or null), priority (HIGH|NORMAL|LOW), founderTimezone?, founderAvailability[{start,end}]?,
founderAvailabilityText?` (the founder's message, if they said when they're free) `, referenceTime?, maxSuggestions (3)`.

Output: `slots[{rank, start, end, founderLocalStart, score, reasons[]}], requiresApproval: true,
founderAvailabilitySource, constraintsApplied[]`. Slot `start`/`end` map directly to
`GET /api/calendar/availability`.

### 7. `email-response`
`purpose (ACKNOWLEDGE | REQUEST_INFO | PROPOSE_MEETING | CONFIRM_MEETING | DECLINE | GENERAL_REPLY),
recipient{email,name}, investor{name,title,firm,email}, companyName, thread{subject,body}?,
questions[]` (REQUEST_INFO) `, proposedSlots[{start,end}]` (PROPOSE_MEETING) `,
meeting{start,end,locationOrLink}` (CONFIRM_MEETING) `, timezone, founderTimezone?, instructions?, tone (WARM|FORMAL|BRIEF)`.

Output: `recipient, subject, body, purpose, requiresApproval: true` — matches the frontend draft
contract. `recipient` always equals the input recipient.

### 8. `action`
`workflowId, event, emailId?, threadId?, pitch{pitchId, companyName, sector, stage, founderName,
founderEmail, amountRequested, website, briefUrl, claimStatusSummary, openQuestionCount}?,
meeting{start,end,timezone,title,attendeeEmails[],addVideoConference}?, email{recipient,subject,body,
threadId,inReplyToMessageId}?, approval{approvedBy, approvedAt}?`.

Output: `actions[{actionId, type, target, payload, requiresApproval, approved, dependsOn[], reason}],
blockedActionIds[]`.

## 4. Executing actions (backend)

- **Never execute an action where `requiresApproval && !approved`.** These are listed in `blockedActionIds`.
- Run actions in order; run an action only after everything in its `dependsOn` succeeded.
- `actionId` is stable for the same workflow + event + action — store it and skip if already executed.
- Payload formats:
  - `GMAIL_ADD_LABELS` / `GMAIL_REMOVE_LABELS`: `{labels[], scope: message|thread, createIfMissing}` → Gmail `users.messages.modify` (create labels with `users.labels.create` if missing). Default labels: `Pitsch/Pitch`, `Pitsch/Follow-up`, `Pitsch/Processing`, `Pitsch/Needs Review`, `Pitsch/Reviewed`, `Pitsch/Meeting Scheduled`, `Pitsch/Archived`.
  - `CALENDAR_CREATE_EVENT`: `{event, queryParams}` → `events.insert(calendarId="primary", body=event, **queryParams)`. `event` is already in Google Calendar API format.
  - `GMAIL_SEND_EMAIL`: `{to, subject, body, threadId, inReplyTo?, references?}` → build a MIME message (set `In-Reply-To`/`References` headers when present) and `users.messages.send`.
  - `SHEETS_UPSERT_ROW`: `{keyColumn: "Pitch ID", columns[], values{}}` → find the row by key, update only the given columns (or append).

## 5. Human-in-the-loop summary

| Autonomous | Needs investor approval | Never delegated to AI |
|---|---|---|
| Classify, label, extract, research, verify, build brief, update pipeline sheet, rank slots, draft emails | Send email, create calendar event / invite | Investment decision, amount, recommendation |
