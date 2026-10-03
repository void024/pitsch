# Evaluation

This directory is reserved for the labelled regression set for Agent #1.

Recommended case fields:

- `id`
- `email`
- `candidate_pitches`
- `expected_category`
- `expected_previous_pitch_id`
- `expected_action`
- `expected_human_review`

Start with anonymized real emails when available, then grow toward 50+ cases covering:

- obvious pitches
- vague pitches
- forwarded pitches
- revised decks
- founder replies
- meeting requests
- closed fundraising
- multiple companies
- newsletters/spam/recruiters/vendors
- missing or unreadable attachments
- personal email domains
- prompt injection attempts
- long/HTML/Unicode email bodies
- ambiguous pitch matching

Do not put real credentials or unnecessary personally identifying information into the dataset.

## Other agents

The offline tests check our logic with a fake model. Real-model quality needs labelled sets:

| Agent | Labelled set | Metrics |
|---|---|---|
| Document | 10–20 real decks with hand-written expected claims | claim recall/precision, % quotes verified, missing-info accuracy |
| Research | 10 companies with known facts (funding, founders, competitors) | evidence precision, look-alike confusion rate, dropped-excerpt rate, searches & cost per run |
| Verification | 50+ claim/evidence pairs with expected status | status accuracy, false VERIFIED rate (most important), contradiction recall |
| Analysis | 10 briefs reviewed by a human | uncited statements per brief, recommendation-language rate (target 0), reviewer usefulness score |
| Calendar (text parsing) | 30 availability messages with expected windows | window exact-match rate |
| Email Response | 20 scenarios incl. injection attempts | commitment/link leakage (target 0), reviewer edit distance |

Track tokens, latency and cost per full pitch (sum of `meta` across steps).
