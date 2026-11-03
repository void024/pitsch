"""Test doubles: a scripted LLM and a scripted search provider. No network, no API keys."""

import json
from typing import Callable

from app.core.errors import AgentException
from app.core.llm import LLMResponse
from app.core.search import SearchResult


class FakeLLM:
    """Replies from a script. Each item may be a dict (sent as JSON), a raw string, an
    exception (raised), or a callable(system, user) -> dict|str for context-aware replies."""

    def __init__(self, responses):
        self.responses = list(responses)
        self.calls: list[tuple[str, str]] = []

    def complete_json(self, system: str, user: str) -> LLMResponse:
        self.calls.append((system, user))
        if not self.responses:
            raise AssertionError("FakeLLM ran out of scripted responses")
        r = self.responses.pop(0)
        if callable(r) and not isinstance(r, (dict, str)):
            r = r(system, user)
        if isinstance(r, Exception):
            raise r
        content = r if isinstance(r, str) else json.dumps(r)
        return LLMResponse(content=content, model="fake-model", prompt_tokens=100, completion_tokens=20)


class FakeSearch:
    """Maps query substrings to results; records every query it receives."""

    name = "fake"

    def __init__(self, results_for: Callable[[str], list[SearchResult]] | dict | None = None,
                 fail_with: AgentException | None = None):
        self.results_for = results_for or {}
        self.fail_with = fail_with
        self.queries: list[str] = []

    def search(self, query: str, max_results: int) -> list[SearchResult]:
        self.queries.append(query)
        if self.fail_with:
            raise self.fail_with
        if callable(self.results_for):
            return self.results_for(query)[:max_results]
        out = []
        for key, results in self.results_for.items():
            if key.lower() in query.lower():
                out.extend(results)
        return out[:max_results]
