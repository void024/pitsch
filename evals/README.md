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
