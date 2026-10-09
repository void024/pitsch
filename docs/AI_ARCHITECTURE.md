# AI Architecture

Pitsch keeps its original **eight-agent** design. The agents live in `ai-service/` (FastAPI); the backend's
`WorkflowEngine` decides when each one runs, stores every call as an `agent_execution`, and alone turns their
output into actions. Detailed per-agent behaviour: [`ai-service/docs/agents.md`](../ai-service/docs/agents.md).

## The agents

| # | Agent | Endpoint | Produces | Side effects |
|---|---|---|---|---|
| 1 | Email Classifier | `POST /agents/email-classifier` | Category (new pitch / follow-up / update / not a pitch + subtype), linked pitch from backend-supplied candidates, confidence, reasons, `needsHumanReview`, prompt-injection flag | none |
| 2 | Document | `/agents/document` | Company facts, founders, raise, metrics and quote-backed **claims** from email + PDF/PPTX | none |
| 3 | Research | `/agents/research` | Evidence items: URL, title, published date, retrieved date, verbatim excerpt, source type, freshness | search queries (Tavily) |
| 4 | Verification | `/agents/verification` | Per claim: `SUPPORTED · PARTIALLY_SUPPORTED · UNSUPPORTED · CONTRADICTED · NOT_FOUND`, evidence ids, confidence, reasoning summary, independently-verified flag | none |
| 5 | Analysis | `/agents/analysis` | The brief (sections below) + deterministic evidence-support score; **never** an invest / pass verdict | none |
| 6 | Calendar | `/agents/calendar` | Ranked slots inside working hours that avoid busy times supplied by the backend | none (never books) |
| 7 | Email Response | `/agents/email-response` | Draft subject/body for the founder | none (never sends) |
| 8 | Action | `/agents/action` | Suggested Gmail labels, pipeline-sheet field values, `requiresApproval` per item | none (backend executes) |

Pipeline for a new pitch: Classifier → (user decides) → Document → Research → Verification → Analysis → (user)
→ Calendar and/or Email Response → Action. Steps are separate jobs, persisted after each agent, so a failure
resumes from the failed agent (`RETRY`) instead of re-running and re-paying for the whole chain.

## Contract

Every endpoint takes `{executionId, traceId, input}` and returns:

```json
{ "success": true, "agent": "verification", "data": { … }, "error": null,
  "meta": { "executionId": "wf-42:verification:3", "traceId": "…", "schemaVersion": "1.0", "model": "gemini-flash-latest",
            "attempts": 1, "promptTokens": 5120, "completionTokens": 1400, "latencyMs": 9300,
            "estimatedCostUsd": 0.0041, "inputHash": "sha256…", "fallbackUsed": false } }
```

Errors are typed (`INVALID_INPUT`, `INSUFFICIENT_INPUT`, `LLM_TIMEOUT`, `LLM_API_ERROR`, `MALFORMED_LLM_OUTPUT`,
`DOCUMENT_UNREADABLE`, `SEARCH_FAILED`, `RESEARCH_FAILED`, `INTERNAL_ERROR`) with `retryable`. The backend's
`AgentRunner` stores one `agent_executions` row per call: workflow, agent, step, model, status, attempts,
retries, prompt/completion tokens, estimated cost, latency, input hash, error code/category, and (for
`AGENT_IO_RETENTION_DAYS`) the input/output JSON. The UI shows these per workflow ("Agent log & approvals") and
the dashboard aggregates them per month. Base64 file contents are replaced by a length marker before the input is
stored. Each successful agent output is saved on the workflow (classification, document, research, verification,
brief), so a retried or resumed job skips every agent that already succeeded instead of calling the model again.

## LLM provider abstraction

`app/core/llm.py` talks to any OpenAI-compatible Chat Completions API:

| Setting | Meaning |
|---|---|
| `LLM_PROVIDER` | `gemini` (Google's OpenAI-compatible endpoint, provider defaults for temperature/reasoning), `openai`, or `other` with `LLM_BASE_URL` (Groq, Ollama, vLLM, Azure-compatible gateways) |
| `LLM_MODEL` | default model; per-agent overrides `CLASSIFIER_LLM_MODEL`, `DOCUMENT_LLM_MODEL`, `RESEARCH_LLM_MODEL`, `VERIFICATION_LLM_MODEL`, `ANALYSIS_LLM_MODEL`, `EMAIL_RESPONSE_LLM_MODEL`, `CALENDAR_LLM_MODEL` (e.g. a lite model for classification, the strongest for analysis) |
| `LLM_FALLBACK_MODEL` | used only after the primary fails with a provider error (timeout, 5xx, 429 after retries, unknown model); `meta.fallbackUsed=true` |
| `LLM_MAX_RETRIES`, `LLM_TIMEOUT_SECONDS` | per call; backoff honours `Retry-After` |
| `LLM_MAX_OUTPUT_TOKENS` | hard cap per call (`max_completion_tokens` for OpenAI, `max_tokens` otherwise) |
| `LLM_JSON_MODE` | request JSON output; responses are always parsed and validated with Pydantic regardless |
| `LLM_PRICES` | `model=input/output` USD per million tokens → `estimatedCostUsd` (no price → `null`, never a guess) |

When the primary and fallback both fail the agent returns a typed error; the workflow becomes `FAILED` with a Retry
action. **No agent ever substitutes made-up output.**

## Guarding against prompt injection

Emails, decks and web pages are untrusted.

1. **Fencing.** Untrusted text is placed in clearly delimited blocks; system prompts say that content inside them
   is data to analyse and that instructions inside it must be ignored and reported.
2. **Detection.** `app/core/injection.py` scans emails and documents for instruction-override, exfiltration,
   role-play and tool-invocation patterns (tuned against `evals/datasets/injection.jsonl` for recall without
   flagging ordinary AI-startup pitches). Flags appear as `promptInjectionSuspected` + signals, force human review
   and are audited.
3. **Structured output.** Free-text output fields are bounded; every response must validate against a schema;
   ids must be ids the input contained; quotes and excerpts must appear verbatim in the source text; URLs must
   appear in the search results — otherwise the item is dropped and a warning added.
4. **No capabilities.** Even a fully "convinced" model can only write text into JSON fields. It has no tools that
   act; the backend chooses recipients, validates labels against a prefix, maps pipeline fields, checks slots
   against free/busy and requires human approval for email and calendar changes.

## Research and evidence

* Bounded loop: at most `RESEARCH_MAX_QUERIES` queries over `RESEARCH_MAX_ROUNDS` rounds, `SEARCH_RESULTS_PER_QUERY`
  results each, and an overall `RESEARCH_DEADLINE_SECONDS`. When the workspace's monthly `RESEARCH_QUERIES` quota
  is used up the backend skips research and the brief says it is based on the pitch only.
* Evidence comes only from search results (title, URL, published date when known, retrieval timestamp, excerpt).
  Sources older than `RESEARCH_STALENESS_DAYS` are marked possibly outdated. Non-public URLs are discarded.
* The model's own knowledge is never evidence: anything not backed by a result is labelled *AI inference* and
  cannot make a claim SUPPORTED. Company-controlled sources (the startup's own site, press releases) can at most
  make a claim *partially* supported, and `independentlyVerified` stays false.
* No search key → research returns no evidence and claims end as `NOT_FOUND` / `UNSUPPORTED`, visibly.

## The brief

Sections: Executive summary · Company overview · Founders · Problem · Solution · Product · Traction (as stated) ·
Business model · Market · Competition · Fundraising (amount, instrument, valuation, use of funds) · Claims & evidence
matrix · Risks · Opportunities (conditional) · Missing information · Questions to ask the founder · Research sources ·
Evidence support (AI confidence) · Last updated.

Every statement carries provenance (`PITCH`, `COMPANY`, `EXTERNAL`, `AI_INFERENCE`) and citations. "AI confidence"
is computed deterministically from the claim assessments and evidence coverage (`LOW/MEDIUM/HIGH`, score, reasons)
— it describes how well the pitch is supported by evidence, not whether to invest. A guardrail
(`app/core/guardrails.py`) rejects recommendation and commitment language ("we should invest", "pass on this",
"we will fund") in analysis and verification output; the step is retried and fails rather than shipping a verdict.

## Cost and abuse controls

| Control | Where |
|---|---|
| Per-minute AI-triggering request limit per user | backend `RATE_LIMIT_AI_PER_MINUTE` |
| Monthly quotas per workspace (workflows, pitches, tokens, research queries, document pages, storage) | backend `EntitlementService` from `plans.limits_json`; `QUOTA_EXCEEDED` + notification |
| Max output tokens per call, request body size, document page/char limits | AI service settings |
| Re-use of successful agent results on retries | step outputs stored on the workflow |
| Classification only when needed | deduplicated ingestion; non-pitch emails stop after the classifier |

## Evaluation

`ai-service/evals/` contains synthetic datasets and a runner:

* `python -m evals.run_evals` (offline, runs in CI): prompt-injection recall and false-positive rate,
  recommendation-guardrail accuracy, verification vocabulary mapping. Thresholds fail the build.
* `python -m evals.run_evals --suite live --report evals/reports/<date>.json` (needs an LLM key): classifier
  accuracy, pitch precision/recall, follow-up linking accuracy, review-routing rate, injection flag rate,
  verification false-SUPPORTED rate, structured-output validity, latency, tokens and cost. Run before changing a
  prompt, model or provider and compare reports.

Details and dataset descriptions: [`ai-service/evals/README.md`](../ai-service/evals/README.md).
