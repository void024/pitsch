"""Deterministic prompt-injection heuristics for untrusted text (emails, decks, web pages).

Defence in layers:
  1. Untrusted text is always fenced as data (`text.fence`) and system prompts say it is not instructions.
  2. Model outputs are schema-validated and IDs/URLs/quotes are re-checked by code.
  3. The AI cannot act: every side effect is executed by the backend after explicit user approval.
  4. This module FLAGS likely injection attempts so the email is routed to a human (needs review) and the
     attempt is audit-logged by the backend. It never blocks content and never decides anything on its own.

Signals are named, so the review reason is explainable ("asks the assistant to ignore its instructions").
"""

import re
import unicodedata

_RULES: list[tuple[str, str]] = [
    ("override_instructions",
     r"\b(ignore|disregard|forget|override|bypass)\b[^.\n]{0,40}\b(previous|prior|above|earlier|all|your|system|the)\b"
     r"[^.\n]{0,20}\b(instructions?|prompts?|rules?|guidelines?|directives?)\b"),
    ("role_reassignment",
     r"\b(you are now|from now on,? you (are|will|must|should)|pretend (that )?you are|you must now act as|"
     r"enter (developer|dan|jailbreak) mode)\b"),
    ("system_prompt_probe",
     r"\b(reveal|print|show|repeat|output|leak)\b[^.\n]{0,30}\b(system prompt|hidden instructions?|your instructions|"
     r"initial prompt)\b"),
    ("fake_role_marker",
     r"(^|\n)\s*(system|assistant|developer)\s*:\s|<\s*/?\s*(system|assistant|instructions?)\s*>|\[\s*(system|inst)\s*\]"
     r"|<\|im_start\|>|<\|endoftext\|>"),
    ("classification_steering",
     r"\b(classify|categori[sz]e|label)\b[^.\n]{0,30}\b(this|the)\s+(email|message)\b[^.\n]{0,30}"
     r"\bas\b[^.\n]{0,20}\b(new[_ ]pitch|pitch|not[_ ]pitch|approved|safe|high priority)\b"),
    ("action_steering",
     r"\b(send|forward|email|reply)\b[^.\n]{0,40}\b(to|at)\b[^.\n]{0,20}[\w.+-]+@[\w-]+\.[\w.]+[^.\n]{0,40}"
     r"\b(immediately|automatically|without (asking|approval|review|confirmation))\b"),
    ("approval_bypass",
     r"\b(no|without)\s+(any\s+)?(human|manual|user|investor)\s+(approval|review|confirmation)\b"
     r"|\b(skip|bypass)\s+(the\s+)?(approval|review|confirmation)\b"),
    ("tool_invocation",
     r"\byou\s+(must|should|need to|will|are to)\s+(call|invoke|execute|run)\b[^.\n]{0,20}\b(tool|function|command|shell)\b"),
    ("hidden_text_marker",
     r"[\u200b\u200c\u200d\u2060\ufeff]"),
]
_COMPILED = [(name, re.compile(pattern, re.IGNORECASE)) for name, pattern in _RULES]

DESCRIPTIONS = {
    "override_instructions": "asks the assistant to ignore its instructions",
    "role_reassignment": "tries to give the assistant a new role",
    "system_prompt_probe": "asks for the system prompt",
    "fake_role_marker": "contains fake system/assistant role markers",
    "classification_steering": "tells the assistant how to classify the email",
    "action_steering": "asks for an action to be taken automatically",
    "approval_bypass": "claims no approval or review is needed",
    "tool_invocation": "asks the assistant to run tools or commands",
    "hidden_text_marker": "contains invisible characters often used to hide instructions",
}


def detect_prompt_injection(*texts: str | None) -> list[str]:
    """Names of the injection signals found in any of `texts` (empty list = nothing suspicious)."""
    found: list[str] = []
    for text in texts:
        if not text:
            continue
        raw = text[:200_000]
        normalised = unicodedata.normalize("NFKC", raw)
        for name, rx in _COMPILED:
            if name not in found and (rx.search(normalised) or (name == "hidden_text_marker" and rx.search(raw))):
                found.append(name)
    return found


def describe(signals: list[str]) -> str:
    return "; ".join(DESCRIPTIONS.get(s, s) for s in signals)
