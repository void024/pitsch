import re

from app.agents.classifier.schemas import EmailInput, MatchSignal

FREE_EMAIL_DOMAINS = {
    "gmail.com", "googlemail.com", "yahoo.com", "yahoo.co.in", "outlook.com", "hotmail.com",
    "live.com", "icloud.com", "proton.me", "protonmail.com", "rediffmail.com", "aol.com", "zoho.com",
}


def _domain(email: str) -> str:
    return email.rsplit("@", 1)[-1].lower() if "@" in email else ""


def _mentions(text: str, name: str) -> bool:
    if len(name.strip()) < 3:
        return False
    return re.search(rf"(?<!\w){re.escape(name.strip().lower())}(?!\w)", text) is not None


def score_candidates(email: EmailInput) -> list[MatchSignal]:
    """Deterministic evidence linking this email to existing pitches. The LLM sees these
    signals but may only choose among the candidate IDs — it never invents one."""
    text = f"{email.subject}\n{email.body}".lower()
    sender_domain = _domain(email.sender.email)
    results: list[MatchSignal] = []

    for c in email.candidate_pitches:
        signals: list[str] = []
        score = 0.0

        if email.thread_id and email.thread_id in c.thread_ids:
            signals.append("SAME_THREAD")
            score += 0.6

        if email.sender.email in {e.strip().lower() for e in c.founder_emails}:
            signals.append("SENDER_IS_KNOWN_FOUNDER")
            score += 0.3
        elif (c.company_domain and sender_domain == c.company_domain.lower()
              and sender_domain not in FREE_EMAIL_DOMAINS):
            signals.append("SENDER_DOMAIN_MATCHES_COMPANY")
            score += 0.2

        if _mentions(text, c.company_name):
            signals.append("COMPANY_NAME_MENTIONED")
            score += 0.2

        if signals:
            results.append(MatchSignal(pitch_id=c.pitch_id, signals=signals, score=round(min(score, 1.0), 2)))

    return sorted(results, key=lambda m: m.score, reverse=True)
