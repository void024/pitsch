import logging
import re
import time
from dataclasses import dataclass
from typing import Callable, Protocol, TypeVar

from openai import APIConnectionError, APIStatusError, APITimeoutError, OpenAI, RateLimitError
from pydantic import BaseModel, ValidationError

from app.core.errors import AgentException, ErrorCode

logger = logging.getLogger(__name__)
ModelT = TypeVar("ModelT", bound=BaseModel)
_FENCE = re.compile(r"^```(?:json)?\s*|\s*```$")


@dataclass
class LLMResponse:
    content: str
    model: str
    prompt_tokens: int
    completion_tokens: int


class LLMClient(Protocol):
    def complete_json(self, system: str, user: str) -> LLMResponse: ...


class OpenAICompatibleClient:
    """Works with any OpenAI-compatible API: OpenAI, Gemini, Groq, OpenRouter, Ollama, etc."""

    def __init__(
        self,
        api_key: str,
        model: str,
        base_url: str | None = None,
        timeout: float = 30.0,
        temperature: float | None = 0.0,
        json_mode: bool = True,
    ):
        # max_retries=0: retries are handled by call_structured so they are counted and logged.
        self._client = OpenAI(api_key=api_key, base_url=base_url, timeout=timeout, max_retries=0)
        self._model = model
        self._temperature = temperature
        self._json_mode = json_mode

    def complete_json(self, system: str, user: str) -> LLMResponse:
        kwargs: dict = {}
        if self._temperature is not None:
            kwargs["temperature"] = self._temperature
        if self._json_mode:
            kwargs["response_format"] = {"type": "json_object"}
        try:
            resp = self._client.chat.completions.create(
                model=self._model,
                messages=[{"role": "system", "content": system}, {"role": "user", "content": user}],
                **kwargs,
            )
        except APITimeoutError as exc:  # must come before APIConnectionError (subclass)
            raise AgentException(ErrorCode.LLM_TIMEOUT, "LLM request timed out", retryable=True) from exc
        except APIConnectionError as exc:
            raise AgentException(ErrorCode.LLM_API_ERROR, "Could not reach LLM provider", retryable=True) from exc
        except RateLimitError as exc:  # must come before APIStatusError (subclass)
            raise AgentException(ErrorCode.LLM_API_ERROR, "LLM provider rate limit hit", retryable=True) from exc
        except APIStatusError as exc:
            raise AgentException(
                ErrorCode.LLM_API_ERROR,
                f"LLM provider returned HTTP {exc.status_code}",
                retryable=exc.status_code >= 500,
            ) from exc

        usage = resp.usage
        return LLMResponse(
            content=resp.choices[0].message.content or "",
            model=resp.model,
            prompt_tokens=usage.prompt_tokens if usage else 0,
            completion_tokens=usage.completion_tokens if usage else 0,
        )


@dataclass
class CallStats:
    model: str | None = None
    attempts: int = 0
    prompt_tokens: int = 0
    completion_tokens: int = 0


def _strip_fences(text: str) -> str:
    return _FENCE.sub("", text.strip())


def _summarize_validation(err: ValidationError, limit: int = 5) -> str:
    parts = []
    for e in err.errors()[:limit]:
        loc = ".".join(str(p) for p in e["loc"]) or "(root)"
        parts.append(f"{loc}: {e['msg']}")
    return "; ".join(parts)


def call_structured(
    llm: LLMClient,
    system: str,
    user: str,
    schema: type[ModelT],
    *,
    stats: CallStats,
    max_retries: int = 2,
    backoff_seconds: float = 0.5,
    sleep: Callable[[float], None] = time.sleep,
) -> ModelT:
    """Call the LLM and validate its JSON against `schema`.

    - Retryable provider errors (timeouts, 5xx, rate limits): exponential backoff.
    - Malformed output: retry once per attempt, telling the model what was wrong.
    - Non-retryable errors: raised immediately.
    `stats` is mutated so callers can report attempts and token usage even on failure.
    """
    prompt = user
    last_error: AgentException | None = None

    for attempt in range(max_retries + 1):
        stats.attempts += 1
        try:
            resp = llm.complete_json(system, prompt)
        except AgentException as exc:
            last_error = exc
            if not exc.retryable or attempt == max_retries:
                raise
            logger.warning("LLM call failed, retrying",
                           extra={"event": "llm_retry", "error_code": exc.code.value, "attempt": attempt + 1})
            sleep(backoff_seconds * (2 ** attempt))
            continue

        stats.model = resp.model
        stats.prompt_tokens += resp.prompt_tokens
        stats.completion_tokens += resp.completion_tokens

        try:
            return schema.model_validate_json(_strip_fences(resp.content))
        except ValidationError as exc:
            problems = _summarize_validation(exc)
            last_error = AgentException(ErrorCode.MALFORMED_LLM_OUTPUT, f"LLM output failed validation: {problems}")
            logger.warning("LLM output failed validation",
                           extra={"event": "llm_malformed", "attempt": attempt + 1})
            prompt = f"{user}\n\nYour previous response was invalid: {problems}\nReturn only a corrected JSON object."

    assert last_error is not None
    raise last_error
