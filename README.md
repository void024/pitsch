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

```bash
python -m venv .venv
# Windows:
.venv\Scripts\activate
# Linux/macOS:
source .venv/bin/activate

pip install -r requirements.txt
copy .env.example .env
# edit .env with your LLM credentials/model

pytest -q
uvicorn app.main:app --reload --port 8000
```

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
├── .gitignore
├── pytest.ini
└── requirements.txt
```

## Important integration contract

Spring Boot should generate a deterministic `executionId`, for example:

```text
<workflowId>:EMAIL_CLASSIFIER:<stepNumber>
```

and a request-scoped `traceId`.

The AI service returns `success=false` inside the response envelope for handled agent failures;
Spring Boot can inspect `error.retryable` to decide whether the workflow step should be retried.

## Security

Email text is untrusted input. It is fenced and neutralized before being placed in the prompt.
The agent must never follow instructions embedded inside email content.

Never put API keys, OAuth credentials, full email bodies or extracted deck text into logs.

## Next steps

After Agent #1 is frozen:

1. expand the labelled regression/evaluation set,
2. measure real-model accuracy, latency and token usage,
3. integrate the endpoint with Spring Boot,
4. then build Agent #2 — the Document Agent.
