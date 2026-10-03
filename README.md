# Pitsch AI Service

**Automating investment research and deal workflows — keeping decisions human.**

Pitsch takes an unstructured startup pitch sitting in an investor's inbox, identifies it, researches
it across sources, cross-examines the founder's claims, structures the evidence into a decision-ready
brief, drafts communication and proposes meeting times. The investor makes every consequential
decision.

This repository is the **AI service**: eight agents exposed as REST endpoints, called only by the
Spring Boot backend.

```
                 Spring Boot backend (orchestrator, state, Gmail/Calendar/Sheets, PostgreSQL)
                                          │  REST (camelCase JSON)
 ┌────────────────────────────────────────┼────────────────────────────────────────────┐
 │  Pitsch AI service                     ▼                                            │
 │  1 Email Classifier ─► 2 Document ─► 3 Research ─► 4 Verification ─► 5 Analysis     │
 │                                                                         │           │
 │                               6 Calendar ─► 7 Email Response ─► 8 Action            │
 └─────────────────────────────────────────────────────────────────────────────────────┘
        Each agent: structured input ─► structured AgentResult. No DB, no UI, no decisions.
```

## The agents

| # | Agent | Endpoint | What it does | Uses LLM? |
|---|---|---|---|---|
| 1 | Email Classifier | `POST /agents/email-classifier` | Pitch / follow-up / update / not a pitch; links follow-ups to existing pitches | Yes |
| 2 | Document | `POST /agents/document` | Reads PDF/PPTX/email; extracts company, founders, raise, metrics and quote-backed **claims** | Yes |
| 3 | Research | `POST /agents/research` | Bounded search loop; collects **evidence** with verbatim excerpts, sources, dates | Yes + web search |
| 4 | Verification | `POST /agents/verification` | Checks each claim: VERIFIED / PARTIALLY_VERIFIED / UNVERIFIED / CONTRADICTED / NOT_FOUND | Yes |
| 5 | Analysis | `POST /agents/analysis` | Builds the research brief: overview, claims matrix, market, competitors, risks, **open questions** — no recommendation | Yes |
| 6 | Calendar | `POST /agents/calendar` | Ranks meeting slots with explanations; never books | Only to read "I'm free Tue afternoon" |
| 7 | Email Response | `POST /agents/email-response` | Drafts founder emails; never sends | Yes |
| 8 | Action | `POST /agents/action` | Builds Gmail/Calendar/Sheets payloads + approval flags for the backend to execute | No |

How each agent works and why: **[docs/agents.md](docs/agents.md)**
How the backend should call them: **[docs/integration.md](docs/integration.md)**
Real request/response examples for every step: **[docs/examples/](docs/examples/)** · sample brief: [sample-brief.md](docs/examples/sample-brief.md)

## Design principles

1. **The model interprets; code decides.** Workflow actions, IDs, times, recipients and statuses are
   set or checked by deterministic code.
2. **Trust but verify every model output.** Quotes and excerpts must exist in the source text; IDs
   must be ones the code issued; URLs must appear in the input. Anything else is dropped or flagged.
3. **Claims are not facts.** Every item carries provenance: 🟣 Pitch · 🔵 Company · 🟢 External · 🟠 AI inference.
4. **No investment recommendations.** Recommendation language is detected and rewritten; the brief
   ends with open questions for the investor instead of a verdict.
5. **Humans approve anything external.** Sending email and creating meetings are always `requiresApproval`.
6. **Untrusted text stays data.** Emails, decks and web pages are fenced so they can't inject instructions.
7. **Fail visibly.** Uncertain results set `needsHumanReview` with reasons; failures return a typed error with `retryable`.

## Run locally

Requires **Python 3.10+**.

```bash
python -m venv .venv

# Windows
.venv\Scripts\activate
copy .env.example .env

# macOS / Linux
source .venv/bin/activate
cp .env.example .env

pip install -r requirements-dev.txt    # runtime + test dependencies
# edit .env: LLM_API_KEY, LLM_MODEL (and TAVILY_API_KEY for web research)

pytest -q                               # 100% offline: fake LLM + fake search, no keys needed
uvicorn app.main:app --reload --port 8000
```

Interactive API docs: `http://localhost:8000/docs` · Health check: `GET /health`

The service refuses to start if `LLM_API_KEY` / `LLM_MODEL` are missing. Without `TAVILY_API_KEY`,
research still runs but collects no web evidence (and flags that for review).

## Common envelope

Every endpoint takes and returns the same envelope:

```json
// request
{ "executionId": "102:RESEARCH_AGENT:4", "traceId": "req-abc", "input": { ... } }

// response
{ "success": true, "agent": "RESEARCH_AGENT", "data": { ... }, "error": null,
  "meta": { "executionId": "...", "traceId": "...", "model": "...", "attempts": 2,
            "promptTokens": 5120, "completionTokens": 830, "latencyMs": 4210 } }

// handled failure (HTTP 200)
{ "success": false, "agent": "RESEARCH_AGENT", "data": null,
  "error": { "code": "SEARCH_FAILED", "message": "All web searches failed.", "retryable": true }, "meta": { ... } }
```

Invalid input returns HTTP 422 with `error.code = "INVALID_INPUT"`.

## Repository layout

```text
pitsch-ai/
├── app/
│   ├── main.py                 # FastAPI app, startup config check, validation errors
│   ├── config.py               # all settings (from .env)
│   ├── api/routes.py           # one endpoint per agent
│   ├── core/
│   │   ├── llm.py              # OpenAI-compatible client, retries, JSON validation + repair
│   │   ├── search.py           # SearchProvider interface + Tavily
│   │   ├── guardrails.py       # no-recommendation / no-commitment detectors
│   │   ├── text.py             # quote/URL/domain checks, prompt fencing
│   │   ├── errors.py           # error codes
│   │   └── logging.py          # JSON logs, never content
│   ├── schemas/common.py       # AgentRequest / AgentResult envelope
│   └── agents/
│       ├── base.py             # shared run wrapper (envelope, meta, logs, crash handling)
│       ├── classifier/  document/  research/  verification/
│       └── analysis/  calendar/  email_response/  action/
├── tests/                      # one file per agent + end-to-end pipeline test
├── docs/
│   ├── agents.md               # how each agent works (read this first)
│   ├── integration.md          # contract for the Spring Boot backend
│   ├── email-classifier.md
│   └── examples/               # recorded request/response for every step
├── evals/                      # labelled evaluation sets (to be built)
├── .github/workflows/tests.yml # CI
├── .env.example  requirements.txt  requirements-dev.txt  pytest.ini
```

## Working on this repo

- `main` should always pass `pytest`. CI checks every push and pull request.
- Work on a branch per change, e.g. `agent/research-serper`, `fix/calendar-lunch`, and open a pull request.
- Never commit `.env`, API keys, OAuth tokens or real email/deck content.
- If you change an agent's input/output schema, regenerate the examples
  (`PITSCH_RECORD_EXAMPLES=docs/examples pytest tests/test_pipeline_e2e.py`), update
  `docs/integration.md`, and tell the backend team — the schemas are the integration contract.

## Status and next steps

- [x] All eight agents with offline tests (fake LLM/search) and an end-to-end pipeline test
- [ ] Real-model evaluation sets in `evals/` (accuracy, latency, cost per pitch)
- [ ] Calibrate `CLASSIFIER_REVIEW_THRESHOLD` on labelled emails
- [ ] Spring Boot integration test against a running service
- [ ] Optional: OCR for scanned decks; a second search provider for cross-checking
