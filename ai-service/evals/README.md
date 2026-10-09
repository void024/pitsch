# Evaluation

Two suites, one runner (`evals/run_evals.py`):

| Suite | What it measures | Needs | Where it runs |
|---|---|---|---|
| `offline` (default) | Deterministic safety components: prompt-injection detector recall / false-positive rate, investment-recommendation guardrail accuracy, verification vocabulary mapping | nothing | CI (`tests/test_evals_offline.py`) — a regression fails the build |
| `live` | Real-model quality of the Email Classifier and Verification Agent: category accuracy, pitch precision/recall, follow-up linking, required-review rate, injection flag rate, structured-output validity, **false SUPPORTED rate** (hallucinated support), latency, tokens, cost | `LLM_API_KEY`, `LLM_MODEL` (and `LLM_PRICES` for cost) | manually, before changing a prompt / model / provider |

```bash
cd ai-service
python -m evals.run_evals                                   # offline, exits 1 if a threshold fails
python -m evals.run_evals --suite live --report evals/reports/$(date +%F)-gemini.json
```

Offline thresholds (`THRESHOLDS` in the runner): injection recall ≥ 0.90, injection false-positive rate ≤ 0.10,
guardrail accuracy = 1.0, assessment mapping complete. Compare live reports over time; the most important live
metric is `verification.false_supported_rate` (a claim shown as SUPPORTED that is not) — target 0.

## Datasets (`evals/datasets/*.jsonl`)

All cases are synthetic. Never add real credentials or unnecessary personal data.

- `classifier.jsonl` — emails with expected category, linked pitch, whether human review is required and whether
  the email contains an injection attempt. Covers: obvious / vague pitches, forwarded pitches, revised decks,
  founder replies, meeting requests, closed rounds, multiple companies, newsletters / spam / recruiters / vendors /
  events, unreadable attachments, personal email domains, prompt injection, long and Unicode bodies, same-domain
  non-pitches.
- `injection.jsonl` — attack texts and ordinary pitch texts that look similar (AI products, APIs, "system").
- `guardrails.jsonl` — sentences that must / must not be treated as investment recommendations.
- `verification.jsonl` — claim + evidence pairs with the expected assessment
  (SUPPORTED, PARTIALLY_SUPPORTED, UNSUPPORTED, CONTRADICTED, NOT_FOUND).

Grow the classifier set toward 50+ cases from anonymised real emails; add every production misclassification
that a user corrects as a new case.

## Other agents

The unit tests check our logic with a fake model. Real-model quality for the remaining agents needs labelled sets:

| Agent | Labelled set | Metrics |
|---|---|---|
| Document | 10–20 real decks with hand-written expected claims | claim recall/precision, % quotes verified, missing-info accuracy |
| Research | 10 companies with known facts (funding, founders, competitors) | evidence precision, look-alike confusion rate, dropped-excerpt rate, searches & cost per run |
| Analysis | 10 briefs reviewed by a human | uncited statements per brief, recommendation-language rate (target 0), reviewer usefulness score |
| Calendar (text parsing) | 30 availability messages with expected windows | window exact-match rate |
| Email Response | 20 scenarios incl. injection attempts | commitment/link leakage (target 0), reviewer edit distance |

Track tokens, latency and cost per full pitch (sum of `meta` across steps; `meta.estimatedCostUsd` when
`LLM_PRICES` is configured).
