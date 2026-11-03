"""Web search tool used by the Research Agent.

The agent depends only on the `SearchProvider` protocol, so swapping Tavily for
Serper/Bing/Brave means writing one small class. Tests use a fake provider.
"""

import logging
from dataclasses import dataclass, field
from datetime import datetime
from typing import Protocol

import httpx

from app.core.errors import AgentException, ErrorCode

logger = logging.getLogger(__name__)


@dataclass
class SearchResult:
    url: str
    title: str
    content: str                       # snippet / extracted page text
    published_at: datetime | None = None
    score: float | None = None
    raw: dict = field(default_factory=dict, repr=False)


class SearchProvider(Protocol):
    name: str

    def search(self, query: str, max_results: int) -> list[SearchResult]: ...


def _parse_date(value) -> datetime | None:
    if not value:
        return None
    for parse in (datetime.fromisoformat,
                  lambda v: datetime.strptime(v, "%a, %d %b %Y %H:%M:%S %Z")):
        try:
            return parse(str(value).replace("Z", "+00:00"))
        except ValueError:
            continue
    return None


class TavilySearch:
    """https://docs.tavily.com — search API designed for LLM agents (returns page content)."""

    name = "tavily"
    URL = "https://api.tavily.com/search"

    def __init__(self, api_key: str, timeout: float = 20.0, client: httpx.Client | None = None):
        self._api_key = api_key
        self._client = client or httpx.Client(timeout=timeout)

    def search(self, query: str, max_results: int) -> list[SearchResult]:
        try:
            resp = self._client.post(
                self.URL,
                headers={"Authorization": f"Bearer {self._api_key}"},
                json={"query": query, "max_results": max_results, "search_depth": "advanced",
                      "include_answer": False, "include_raw_content": False},
            )
        except httpx.TimeoutException as exc:
            raise AgentException(ErrorCode.SEARCH_FAILED, "Search request timed out", retryable=True) from exc
        except httpx.HTTPError as exc:
            raise AgentException(ErrorCode.SEARCH_FAILED, "Could not reach search provider", retryable=True) from exc

        if resp.status_code != 200:
            raise AgentException(ErrorCode.SEARCH_FAILED, f"Search provider returned HTTP {resp.status_code}",
                                 retryable=resp.status_code in (429,) or resp.status_code >= 500)

        results = []
        for r in resp.json().get("results", []):
            if not r.get("url"):
                continue
            results.append(SearchResult(
                url=r["url"], title=r.get("title") or r["url"], content=r.get("content") or "",
                published_at=_parse_date(r.get("published_date")), score=r.get("score"), raw=r,
            ))
        return results


class NoSearch:
    """Used when SEARCH_PROVIDER=none: research returns no external evidence (and says so)."""

    name = "none"

    def search(self, query: str, max_results: int) -> list[SearchResult]:
        return []
