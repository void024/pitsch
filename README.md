# Pitsch AI Service — Phase 0 + Agent #1

Pitsch is an AI-assisted startup deal-intelligence system. This repository contains the
AI-service foundation and the first production-oriented agent: the **Email Classification Agent**.

## Architecture boundary

The classifier is intentionally narrow:

- **AI service:** interprets the email and returns structured classification.
- **Spring Boot/orchestrator:** owns workflow state, idempotency, business rules, persistence and external integrations.
- **PostgreSQL:** persistent application state (not accessed by this service).
- **React:** presentation layer (not accessed by this service).
- **Human investor/analyst:** makes the investment decision and approves consequential actions.

The classifier has **no database, Gmail, Calendar, SMTP or investment-decision tools**.

## Agent #1

The agent classifies an email as:

- `NEW_PITCH`
- `PITCH_FOLLOW_UP`
- `PITCH_UPDATE`
- `NOT_PITCH`
- `AMBIGUOUS`

It also extracts useful signals such as forwarded status, companies mentioned, meeting requests,
workflow closure, confidence and candidate-pitch matching evidence.

Application actions are deterministic code:

- `NEW_PITCH` → `ASK_TO_HANDLE`
- existing follow-up + explicit meeting request → `PLAN_MEETING`
- existing follow-up + explicit closure → `COMPLETE_WORKFLOW`
- existing follow-up → `PLAN_EMAIL_RESPONSE`
- `PITCH_UPDATE` → `ASK_TO_HANDLE`
- `NOT_PITCH` → `STOP`
- `AMBIGUOUS` → `ASK_TO_HANDLE`

Low-confidence or internally inconsistent results are escalated to human review.

## Reliability

The service includes:

- Pydantic input/output validation
- OpenAI-compatible LLM client
- provider error classification and exponential backoff
- malformed JSON retry with validation feedback
- deterministic candidate matching
- pitch-ID allowlisting
- prompt-injection fencing
- bounded email body size
- structured JSON logs with no email/deck/prompt content
- execution/trace IDs
- token and latency metadata
- HTTP validation errors
- offline tests with a fake LLM

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

pip install -r requirements-dev.txt   # runtime + test dependencies
# edit .env with your LLM credentials/model

pytest -q                              # runs offline with a fake LLM — no API key needed
uvicorn app.main:app --reload --port 8000
```

Interactive API docs: `http://localhost:8000/docs`

Health check:

```text
GET /health
```

Classifier endpoint:

```text
POST /agents/email-classifier
```

The API accepts/returns camelCase JSON so it can be called cleanly from the Spring Boot backend.

## Repository layout

```text
pitsch-ai/
├── .github/workflows/tests.yml   # CI: runs pytest on every push / PR
├── app/
│   ├── main.py
│   ├── config.py
│   ├── api/
│   │   └── routes.py
│   ├── core/
│   │   ├── errors.py
│   │   ├── llm.py
│   │   └── logging.py
│   ├── schemas/
│   │   └── common.py
│   └── agents/
│       └── classifier/
│           ├── agent.py
│           ├── matcher.py
│           ├── prompt.py
│           └── schemas.py
├── tests/
│   └── test_classifier.py
├── docs/
│   └── email-classifier.md
├── evals/
│   └── README.md
├── .env.example
├── .gitattributes
├── .gitignore
├── pytest.ini
├── requirements.txt              # runtime dependencies
└── requirements-dev.txt          # + test dependencies
```

## Important integration contract

Spring Boot should generate a deterministic `executionId`, for example:

```text
<workflowId>:EMAIL_CLASSIFIER:<stepNumber>
```

and a request-scoped `traceId`.

IDs (`pitchId`, `previousPitchId`, etc.) may be sent as JSON numbers or strings. They are
treated as strings internally and returned as strings (`"42"`); Jackson maps these back to
`Long` without extra configuration.

The AI service returns `success=false` inside the response envelope for handled agent failures;
Spring Boot can inspect `error.retryable` to decide whether the workflow step should be retried.

## Security

Email text is untrusted input. It is fenced and neutralized before being placed in the prompt.
The agent must never follow instructions embedded inside email content.

Never put API keys, OAuth credentials, full email bodies or extracted deck text into logs.

## Working on this repo

- `main` should always pass `pytest`. CI checks every push and pull request.
- Work on a branch per agent or feature, e.g. `agent/document`, `fix/classifier-threshold`,
  then open a pull request into `main`.
- Never commit `.env`, API keys, OAuth tokens or real email/deck content. `.gitignore`
  already excludes `.env` and `evals/data/private/`.
- If you change an agent's input/output schema, update its doc in `docs/` and tell the
  backend team: that schema is the integration contract.

## Next steps

After Agent #1 is frozen:

1. expand the labelled regression/evaluation set,
2. measure real-model accuracy, latency and token usage,
3. integrate the endpoint with Spring Boot,
4. then build Agent #2 — the Document Agent.
