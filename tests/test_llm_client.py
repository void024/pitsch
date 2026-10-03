"""The real OpenAI-compatible client, tested against a mocked Gemini endpoint (no network)."""

import json

import httpx
import pytest

from app.config import Settings
from app.core.errors import AgentException, ErrorCode
from app.core.llm import CallStats, OpenAICompatibleClient, call_structured
from app.agents.classifier.schemas import ClassifierLLMOutput

GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/openai/"
ANSWER = {"category": "NEW_PITCH", "confidence": 0.9, "reason": "Founder pitches a startup."}


def completion(content: str) -> dict:
    return {"id": "x", "object": "chat.completion", "created": 0, "model": "gemini-test",
            "choices": [{"index": 0, "message": {"role": "assistant", "content": content}, "finish_reason": "stop"}],
            "usage": {"prompt_tokens": 120, "completion_tokens": 30, "total_tokens": 150}}


def client_with(handler, **kw) -> tuple[OpenAICompatibleClient, list]:
    seen = []

    def record(request: httpx.Request):
        seen.append(request)
        return handler(request, len(seen))

    http = httpx.Client(transport=httpx.MockTransport(record))
    s = Settings(_env_file=None, llm_provider="gemini", llm_api_key="g-key", llm_model="gemini-test", **kw)
    c = OpenAICompatibleClient(api_key=s.llm_api_key, model=s.llm_model, base_url=s.llm_base_url,
                               timeout=s.llm_timeout_seconds, temperature=s.llm_temperature,
                               json_mode=s.llm_json_mode, reasoning_effort=s.llm_reasoning_effort, http_client=http)
    return c, seen


def test_gemini_preset_values():
    s = Settings(_env_file=None, llm_provider="gemini", llm_api_key="k", llm_model="m")
    assert s.llm_base_url == GEMINI_URL
    assert s.llm_temperature is None and s.llm_reasoning_effort == "low" and s.llm_timeout_seconds == 90


def test_explicit_settings_override_the_preset():
    s = Settings(_env_file=None, llm_provider="gemini", llm_api_key="k", llm_model="m",
                 llm_temperature=0.2, llm_reasoning_effort="medium")
    assert s.llm_temperature == 0.2 and s.llm_reasoning_effort == "medium"


def test_other_providers_keep_old_defaults():
    s = Settings(_env_file=None, llm_api_key="k", llm_model="m")
    assert s.llm_base_url is None and s.llm_temperature == 0.0 and s.llm_reasoning_effort is None


def test_per_agent_model_override():
    s = Settings(_env_file=None, llm_api_key="k", llm_model="main-model", classifier_llm_model="lite-model",
                 research_llm_model="")
    assert s.model_for("classifier") == "lite-model"
    assert s.model_for("research") == "main-model" and s.model_for("analysis") == "main-model"


def test_request_sent_to_gemini_is_correct():
    c, seen = client_with(lambda r, n: httpx.Response(200, json=completion(json.dumps(ANSWER))))
    out = c.complete_json("system prompt", "user prompt")
    req = seen[0]
    body = json.loads(req.content)
    assert str(req.url) == GEMINI_URL + "chat/completions"
    assert req.headers["authorization"] == "Bearer g-key"
    assert body["model"] == "gemini-test" and body["reasoning_effort"] == "low"
    assert "temperature" not in body                                   # Gemini 3: keep default 1.0
    assert body["response_format"] == {"type": "json_object"}
    assert body["messages"][0] == {"role": "system", "content": "system prompt"}
    assert out.prompt_tokens == 120 and json.loads(out.content)["category"] == "NEW_PITCH"


def test_json_mode_rejected_falls_back_once():
    def handler(r, n):
        if "response_format" in json.loads(r.content):
            return httpx.Response(400, json={"error": {"message": "Invalid value for response_format"}})
        return httpx.Response(200, json=completion("```json\n" + json.dumps(ANSWER) + "\n```"))
    c, seen = client_with(handler)
    parsed = call_structured(c, "s", "u", ClassifierLLMOutput, stats=CallStats())
    assert parsed.category.value == "NEW_PITCH" and len(seen) == 2
    c.complete_json("s", "u")
    assert "response_format" not in json.loads(seen[-1].content)       # remembered


def test_rate_limit_waits_for_gemini_retry_delay():
    body = [{"error": {"code": 429, "message": "Resource has been exhausted", "status": "RESOURCE_EXHAUSTED",
                       "details": [{"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "7s"}]}}]

    def handler(r, n):
        return httpx.Response(429, json=body) if n == 1 else httpx.Response(200, json=completion(json.dumps(ANSWER)))
    c, _ = client_with(handler)
    waits = []
    call_structured(c, "s", "u", ClassifierLLMOutput, stats=CallStats(), sleep=waits.append)
    assert waits == [7.0]


def test_retry_after_header_is_capped():
    def handler(r, n):
        return (httpx.Response(429, headers={"retry-after": "500"}, json={"error": {"message": "slow down"}})
                if n == 1 else httpx.Response(200, json=completion(json.dumps(ANSWER))))
    c, _ = client_with(handler)
    waits = []
    call_structured(c, "s", "u", ClassifierLLMOutput, stats=CallStats(), sleep=waits.append)
    assert waits == [30.0]


@pytest.mark.parametrize("status,hint,retryable", [(401, "LLM_API_KEY", False), (404, "LLM_MODEL", False),
                                                   (403, "permission", False), (503, "HTTP 503", True)])
def test_clear_errors(status, hint, retryable):
    c, _ = client_with(lambda r, n: httpx.Response(status, json={"error": {"message": "nope"}}))
    with pytest.raises(AgentException) as e:
        c.complete_json("s", "u")
    assert hint in e.value.message and e.value.retryable is retryable and e.value.code == ErrorCode.LLM_API_ERROR
