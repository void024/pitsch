# How the Pitsch agents work

This is the guide to read first. It explains the patterns every agent uses, then each agent: what
the LLM does, what code does, and which guardrails keep it honest.

## Patterns used everywhere

**1. One agent = one job, structured in and out.**
Each agent has an `Input` schema, an `Output` schema and one `run()` method. Pydantic validates
both. The backend never parses free text.

**2. The LLM is a component, not the boss.**
Inside an agent, the LLM does the part that needs language understanding (classify, extract,
summarise). Everything that must be exact or safe is code: IDs, statuses, times, recipients, URLs,
workflow actions. If the LLM and code disagree, code wins and the result is flagged.

**3. Structured output with automatic repair** (`core/llm.py → call_structured`).
The model must return JSON matching a schema. If it doesn't — or a validator rejects it (for
example, investment advice detected) — the error is sent back to the model ("your previous response
was invalid: …") and it tries again, up to `LLM_MAX_RETRIES`. Provider timeouts and 5xx errors are
retried with exponential backoff.

**4. Trust, but verify** (`core/text.py`).
Models invent quotes, URLs and IDs. So:
- every quote/excerpt must literally exist in the source text (`contains_quote`)
- only IDs the code handed out are accepted (claim IDs, evidence IDs, source IDs, pitch IDs)
- URLs are kept only if they appear in the input

**5. Untrusted text is fenced** (`fence()`).
Emails, decks and web pages go inside tags like `<email>…</email>`, and any copy of that tag inside
the text is neutralised so content can't "close" the block and inject instructions. Prompts say
explicitly: text inside the tags is data.

**6. Provenance on everything.**
🟣 `PITCH` (founder said it) · 🔵 `COMPANY` (their own website / press release) · 🟢 `EXTERNAL`
(independent) · 🟠 `AI_INFERENCE` (model's synthesis, uncited). Code derives this from citations;
the model can't label its own guess as a fact.

**7. Uncertainty is surfaced, not hidden.**
`needsHumanReview` + `reviewReasons` (something needs a human look) and `warnings` (FYI).

**8. Same envelope, same logs** (`agents/base.py`).
`execute()` wraps every agent: envelope, meta (attempts, tokens, latency), JSON logs with IDs and
counts only — never email or deck content — and crash → `INTERNAL_ERROR`.

**9. Tests never call a real model.**
`tests/fakes.py` has a scripted `FakeLLM` and `FakeSearch`, so tests are fast, free and
deterministic. They test *our* logic: what happens when the model is right, wrong, malformed or
malicious.

---

## 1. Email Classifier — `agents/classifier/`

**Job:** is this email a new pitch, a follow-up or update to an existing pitch, or not a pitch?

- **Code first:** `matcher.py` scores candidate pitches (same thread, known founder, same domain,
  company named).
- **LLM:** reads the email + candidates + signals, returns a category, confidence and reason.
- **Code after:** discards pitch IDs that weren't offered; fixes inconsistent combinations;
  multiple companies → `AMBIGUOUS`; low confidence → review; maps category to
  `recommendedAction` with a deterministic function (`decide_action`).

## 2. Document Agent — `agents/document/`

**Job:** turn a deck/email into structured data and a list of checkable **claims**.

- **Code:** extracts text page by page (`extract.py`: PDF via pypdf, PPTX via python-pptx, text).
  Scanned/corrupt files are reported, never guessed. Each page gets a label like `d1:p3`.
- **LLM:** extracts company, founders, raise, metrics, claims (with exact quote + page label), and
  which standard diligence items are missing.
- **Code after:** assigns claim IDs `C1…`; checks every quote against its page (`quoteVerified`);
  repairs a wrong page label if the quote exists elsewhere; removes URLs not in the pitch; flags
  review if many quotes can't be found.

## 3. Research Agent — `agents/research/` (the most "agentic" one)

**Job:** find independent evidence about the company and its claims.

```
plan queries ─► search ─► extract evidence ─► gaps? ─► follow-up searches ─► extract ─► stop
 (code+LLM)    (tool)       (LLM + checks)     (LLM)        (tool)
```

- **Planning:** code always adds standard queries (company, funding, competitors, founders, news);
  the LLM adds claim-specific queries.
- **Tool use:** `SearchProvider` (Tavily by default). One failed search doesn't fail the run; all
  failing → `SEARCH_FAILED` (retryable).
- **Extraction:** the LLM records evidence with a *verbatim excerpt*, marks sources about
  look-alike companies as irrelevant, lists gaps and proposes follow-up queries.
- **Loop control:** hard search budget (`RESEARCH_MAX_QUERIES`) with part reserved for follow-ups,
  and a round limit (`RESEARCH_MAX_ROUNDS`). Agents without limits loop and burn money.
- **Checks:** excerpts not found in the source are dropped (counted in `droppedEvidenceCount`);
  sources are typed `COMPANY` (own site, press-release wires) vs `EXTERNAL`; old sources get
  `possiblyOutdated`.

## 4. Verification Agent — `agents/verification/`

**Job:** for each claim, "can this be substantiated?" — not "is this a good company?".

- **LLM:** picks a status per claim and cites supporting/contradicting evidence IDs.
- **Code enforces the rules:**
  - VERIFIED / PARTIALLY_VERIFIED need valid supporting evidence, CONTRADICTED needs contradicting
    evidence, otherwise downgraded (with a note saying why)
  - VERIFIED needs at least one **EXTERNAL** source — the company repeating its claim isn't verification
  - all-outdated support → `evidenceOutdated`
  - claims the model skipped → UNVERIFIED + review
- No evidence at all → everything NOT_FOUND without spending an LLM call.

## 5. Analysis Agent — `agents/analysis/`

**Job:** the decision-ready brief.

- **Code builds:** company overview table, claims matrix (claims × status × evidence links),
  source list, and open questions from every CONTRADICTED / NOT_FOUND / UNVERIFIED claim, missing
  diligence item and research gap.
- **LLM writes:** summary, market, competition, founders, funding history, risks, extra questions —
  each statement with citations.
- **Guardrails:** recommendation language ("compelling investment", "we recommend passing") fails
  validation → automatic rewrite → still there after retries → the call fails rather than leaking
  advice. Uncited statements are labelled AI inference. Upstream review flags are carried forward.
- `render.py` turns the brief into Markdown (see `examples/sample-brief.md`).

## 6. Calendar Agent — `agents/calendar/`

**Job:** suggest the best meeting slots, with reasons. Never books.

- **Code (`scheduler.py`):** generates candidate slots and applies hard constraints (working hours,
  notice, busy time + buffer, max meetings/day, founder availability, founder local time), then
  scores soft preferences (preferred windows, lunch, back-to-back, day load, deal priority,
  founder comfort) and picks top slots spread across days. Every slot explains its score.
- **LLM (optional):** only to turn "I'm free Thursday afternoon" into exact windows; code validates
  and clips them. If nothing overlaps the founder's windows, it falls back to the investor's free
  slots and flags it.

## 7. Email Response Agent — `agents/email_response/`

**Job:** draft the email; the investor edits and sends.

- **LLM:** writes the body (and a subject for new threads).
- **Code:** sets the recipient (from input only), the `Re:` subject, inserts exact meeting times
  where the model put `{{SLOTS}}` / `{{MEETING_DETAILS}}`, appends the signature, and flags
  commitments ("term sheet", "we will invest"), links or email addresses the investor didn't
  provide — classic prompt-injection payloads from a founder's email.

## 8. Action Agent — `agents/action/` (no LLM)

**Job:** turn workflow events into exact Gmail / Calendar / Sheets payloads for the backend.

- Approval policy in code: labels and the pipeline sheet are autonomous; sending email and creating
  calendar events need `approval` or they're returned in `blockedActionIds`.
- Stable `actionId`s make retries idempotent; `dependsOn` orders actions (label "Meeting Scheduled"
  only after the event exists).

Not every step needs an LLM. Reading an email or applying a label is a function call;
using an LLM there would only add cost and unreliability.

---

## Adding or changing an agent

1. `schemas.py` — input, LLM output (snake_case, with validators), final output (camelCase).
2. `prompt.py` — role, security note, rules, exact output shape.
3. `agent.py` — `run()` → `execute(...)`; do deterministic work before/after `call_structured`.
4. Route in `api/routes.py`.
5. Tests with `FakeLLM`: happy path, wrong/malformed/malicious model output, missing input.
6. Re-record examples and update `docs/integration.md`.
