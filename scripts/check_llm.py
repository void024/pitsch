"""Check that your LLM (e.g. Gemini) and search keys actually work before a demo.

Usage (from the repo root, with .env filled in):
    python -m scripts.check_llm                 # test every model the agents will use (+ Tavily)
    python -m scripts.check_llm --list-models   # show model IDs your key can use
"""

import argparse
import sys
import time

from pydantic import BaseModel, ValidationError

from app.config import get_settings
from app.core.errors import AgentException
from app.core.llm import CallStats, OpenAICompatibleClient, call_structured
from app.core.logging import configure_logging

AGENTS = ["classifier", "document", "research", "verification", "analysis", "email_response", "calendar"]


class _Probe(BaseModel):
    category: str
    company: str


SYSTEM = ("You classify emails for a venture investor. Return only JSON: "
          '{"category": "PITCH" or "NOT_PITCH", "company": "<company name or empty>"}')
USER = "Subject: Raising our seed round\n\nHi! I'm the founder of Krishi AI. We're raising $2M. Deck attached."


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--list-models", action="store_true")
    args = parser.parse_args()
    configure_logging("ERROR")

    try:
        s = get_settings()
    except ValidationError as exc:
        print("Config error:", ", ".join(str(e["loc"][0]).upper() for e in exc.errors()),
              "- copy .env.example to .env and fill it in.")
        return 1

    print(f"Provider: {s.llm_provider} | Base URL: {s.llm_base_url or 'OpenAI default'}")
    print(f"Temperature: {s.llm_temperature if s.llm_temperature is not None else 'provider default'} | "
          f"Reasoning effort: {s.llm_reasoning_effort or 'provider default'} | JSON mode: {s.llm_json_mode}\n")

    if args.list_models:
        client = OpenAICompatibleClient(s.llm_api_key, s.llm_model, base_url=s.llm_base_url)
        try:
            ids = sorted(m.id for m in client._client.models.list())
        except Exception as exc:  # noqa: BLE001 - show whatever the provider said
            print("Could not list models:", exc)
            return 1
        print("\n".join(i.removeprefix("models/") for i in ids))
        return 0

    ok = True
    models = {}
    for agent in AGENTS:
        models.setdefault(s.model_for(agent), []).append(agent)
    for model, agents in models.items():
        client = OpenAICompatibleClient(s.llm_api_key, model, base_url=s.llm_base_url,
                                        timeout=s.llm_timeout_seconds, temperature=s.llm_temperature,
                                        json_mode=s.llm_json_mode, reasoning_effort=s.llm_reasoning_effort)
        stats, started = CallStats(), time.monotonic()
        try:
            out = call_structured(client, SYSTEM, USER, _Probe, stats=stats, max_retries=s.llm_max_retries)
            secs = time.monotonic() - started
            print(f"OK   {model}  ({', '.join(agents)})")
            print(f"     answer={out.category}/{out.company}  {secs:.1f}s  attempts={stats.attempts}  "
                  f"tokens={stats.prompt_tokens}+{stats.completion_tokens}")
        except AgentException as exc:
            ok = False
            print(f"FAIL {model}  ({', '.join(agents)}): {exc.message}")

    print()
    if s.search_provider == "tavily" and s.tavily_api_key:
        from app.core.search import TavilySearch
        try:
            results = TavilySearch(s.tavily_api_key, timeout=s.search_timeout_seconds).search('"Zerodha" funding', 3)
            print(f"OK   Tavily search returned {len(results)} results")
        except AgentException as exc:
            ok = False
            print(f"FAIL Tavily: {exc.message}")
    else:
        print("SKIP Tavily: no TAVILY_API_KEY (research will run without web evidence)")

    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
