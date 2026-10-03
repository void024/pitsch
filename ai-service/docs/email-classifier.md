# Email Classification Agent — Definition of Done

## Purpose

Classify one incoming email and provide structured signals for the backend workflow.
The agent does not execute workflow actions.

## Contract

### Input

`AgentRequest[EmailInput]`

The backend supplies:

- email/thread metadata
- sender
- subject/body
- attachment metadata
- a bounded list of candidate existing pitches

The agent must never invent a `pitch_id`.

### Output

`AgentResult[ClassifierOutput]`

The result contains:

- category
- pitch/follow-up flags
- resolved pitch ID
- detected companies
- forwarding information
- meeting request
- explicit workflow closure
- confidence
- recommended action
- human-review reasons
- warnings
- deterministic match signals
- execution metadata

## Checklist

### Core
- [x] Purpose
- [x] Responsibilities
- [x] Non-responsibilities
- [x] Input schema
- [x] Output schema
- [x] Deterministic action mapping
- [x] Prompt instructions

### Safety
- [x] Candidate-ID allowlist
- [x] Prompt-injection defense
- [x] Confidence threshold
- [x] Human escalation
- [x] Attachment warnings
- [x] No investment decision

### Reliability
- [x] Pydantic validation
- [x] Malformed-output retry
- [x] Provider retry/backoff
- [x] Retryable/non-retryable error classification
- [x] Execution ID
- [x] Trace ID
- [x] Token metadata
- [x] Latency metadata
- [x] Structured logs
- [x] No content logging
- [x] Body size bound

### Testing
- [x] Unit tests
- [x] Matching tests
- [x] Security test
- [x] Reliability tests
- [x] HTTP boundary tests
- [ ] 50+ labelled real/anonymized emails
- [ ] Real-model regression baseline
- [ ] Spring Boot integration test

## Design rule

**The model interprets; deterministic application code decides.**

Do not move workflow transitions, database mutations, email sending or calendar creation
into this agent.
