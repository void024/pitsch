"""Pitsch's core rule: the AI collects and organises evidence; the investor decides.

`find_recommendation` detects language that crosses that line (investment advice, verdicts,
commitments). It is used inside Pydantic validators on LLM outputs, so a violation becomes a
validation error and the model is automatically asked to rewrite without it.
"""

import re

_PATTERNS = [
    r"\b(we|i)\s+(would\s+)?(strongly\s+)?(recommend|suggest|advise|urge)\w*\s+(that\s+you\s+)?(invest\w*|pass\w*|back\w*|fund\w*)\b",
    r"\byou\s+(should|must|ought\s+to)\s+(not\s+)?(invest|pass|back|fund)\b",
    r"\b(should|must)\s+(not\s+)?(invest|be\s+invested)\b",
    r"\brecommend(ation|ed)?\s*(:|is|to)?\s*(invest\w*|pass\w*|buy|proceed\s+with\s+investment)\b",
    r"\b(strong|clear|compelling|attractive|great|good|excellent|poor|bad|risky)\s+investment(\s+opportunity)?\b",
    r"\b(worth|not\s+worth)\s+investing\b",
    r"\binvestment[- ]worthy\b",
    r"\b(strong\s+)?(buy|sell)\s+(signal|rating|recommendation)\b",
    r"\bverdict\s*:",
    r"\b(green|red)[- ]light\s+(this|the)\s+(deal|investment)\b",
]
_RECOMMENDATION = re.compile("|".join(_PATTERNS), re.IGNORECASE)

_COMMITMENTS = [
    r"\bwe\s+(will|are\s+going\s+to|'ll)\s+(invest|fund|lead|participate)\b",
    r"\b(we\s+are|we're)\s+(investing|in\b|committed)\b",
    r"\bterm\s+sheet\b",
    r"\bcommit(ment|ted)?\s+(to\s+)?invest",
    r"\b(offer|agree\s+to)\s+(a\s+)?valuation\b",
    r"\bwe\s+accept\s+your\s+(terms|valuation)\b",
]
_COMMITMENT = re.compile("|".join(_COMMITMENTS), re.IGNORECASE)


def find_recommendation(text: str | None) -> str | None:
    """Return the offending phrase if `text` contains an investment recommendation/verdict."""
    if not text:
        return None
    m = _RECOMMENDATION.search(text)
    return m.group(0) if m else None


def find_commitment(text: str | None) -> str | None:
    """Return the offending phrase if `text` commits the investor to something (used for emails)."""
    if not text:
        return None
    m = _COMMITMENT.search(text)
    return m.group(0) if m else None


def reject_recommendation(text: str | None) -> str | None:
    """Pydantic validator helper: raise so call_structured re-asks the model."""
    phrase = find_recommendation(text)
    if phrase:
        raise ValueError(f"contains investment recommendation language ('{phrase}'); "
                         "describe evidence only and leave the decision to the investor")
    return text
