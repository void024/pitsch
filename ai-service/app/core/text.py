"""Small deterministic text helpers shared by agents.

These are the "trust but verify" tools: the LLM proposes quotes, URLs and excerpts,
and code checks them against the real source text before anything is returned.
"""

import json
import re
import unicodedata
from urllib.parse import urlparse

_WS = re.compile(r"\s+")
_URL = re.compile(r"""https?://[^\s<>"'\)\]]+""", re.IGNORECASE)
_QUOTES = str.maketrans({"‘": "'", "’": "'", "“": '"', "”": '"', "–": "-", "—": "-"})


def normalize(text: str) -> str:
    """Lowercase, unify quotes/dashes/unicode forms and collapse whitespace."""
    text = unicodedata.normalize("NFKC", text or "").translate(_QUOTES)
    return _WS.sub(" ", text).strip().lower()


def contains_quote(source: str, quote: str, min_len: int = 8) -> bool:
    """True if `quote` appears (after normalisation) in `source`.

    Very short quotes are rejected: "50%" appears in lots of places and proves nothing.
    Trailing punctuation differences are tolerated.
    """
    q = normalize(quote).strip(" .,;:!?\"'")
    if len(q) < min_len:
        return False
    return q in normalize(source)


def extract_urls(text: str) -> list[str]:
    seen, out = set(), []
    for m in _URL.findall(text or ""):
        url = m.rstrip(".,;:!?")
        if url.lower() not in seen:
            seen.add(url.lower())
            out.append(url)
    return out


def domain_of(url_or_email: str | None) -> str:
    """'https://www.Acme.ai/x' -> 'acme.ai'; 'a@Acme.ai' -> 'acme.ai'."""
    if not url_or_email:
        return ""
    s = url_or_email.strip().lower()
    if "@" in s and "://" not in s:
        return s.rsplit("@", 1)[-1]
    host = urlparse(s if "://" in s else f"https://{s}").hostname or ""
    return host[4:] if host.startswith("www.") else host


def same_site(domain: str, company_domain: str | None) -> bool:
    """acme.ai, blog.acme.ai and www.acme.ai all count as the company's own site."""
    cd = domain_of(company_domain)
    return bool(cd) and (domain == cd or domain.endswith("." + cd))


def fence(tag: str, text: str) -> str:
    """Wrap untrusted text in <tag>...</tag>, neutralising any copy of that tag inside it,
    so an email/deck/web page cannot 'close' the data block and inject instructions."""
    pattern = re.compile(rf"<\s*/?\s*{re.escape(tag)}\s*>", re.IGNORECASE)
    safe = pattern.sub(lambda m: m.group(0).replace("<", "&lt;").replace(">", "&gt;"), text or "")
    return f"<{tag}>\n{safe}\n</{tag}>"


def dump(obj) -> str:
    return json.dumps(obj, ensure_ascii=False, indent=2, default=str)


def truncate(text: str, max_chars: int, marker: str = "\n[...truncated...]") -> tuple[str, bool]:
    if len(text) <= max_chars:
        return text, False
    return text[:max_chars] + marker, True
