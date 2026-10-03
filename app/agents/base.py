"""Shared run wrapper so every agent behaves identically at the boundary.

Each agent implements one function: (input, stats) -> output data.
`execute` adds what the backend relies on for *every* agent:
  - the common success/failure envelope (AgentResult)
  - execution/trace IDs, attempts, token usage and latency in `meta`
  - structured logs that never contain email/deck/prompt content
  - conversion of unexpected crashes into INTERNAL_ERROR (never an HTML 500)
"""

import logging
import time
from typing import Callable, TypeVar

from pydantic import BaseModel

from app.core.errors import AgentException, ErrorCode
from app.core.llm import CallStats
from app.schemas.common import AgentError, AgentMeta, AgentRequest, AgentResult

logger = logging.getLogger("app.agents")
InputT = TypeVar("InputT")
DataT = TypeVar("DataT", bound=BaseModel)


def execute(
    agent_name: str,
    request: AgentRequest,
    output_type: type[DataT],
    work: Callable[[object, CallStats], DataT],
    log_fields: Callable[[DataT], dict] | None = None,
) -> AgentResult[DataT]:
    started = time.monotonic()
    stats = CallStats()
    ctx = {"agent": agent_name, "trace_id": request.trace_id, "execution_id": request.execution_id}
    logger.info("agent started", extra={**ctx, "event": "agent_started"})

    def meta() -> AgentMeta:
        return AgentMeta(
            execution_id=request.execution_id,
            trace_id=request.trace_id,
            model=stats.model,
            attempts=stats.attempts,
            prompt_tokens=stats.prompt_tokens,
            completion_tokens=stats.completion_tokens,
            latency_ms=int((time.monotonic() - started) * 1000),
        )

    try:
        data = work(request.input, stats)
        extra = log_fields(data) if log_fields else {}
        logger.info("agent completed", extra={**ctx, "event": "agent_completed", **extra})
        return AgentResult[output_type](success=True, agent=agent_name, data=data, meta=meta())
    except AgentException as exc:
        logger.warning("agent failed", extra={**ctx, "event": "agent_failed", "error_code": exc.code.value})
        error = AgentError(code=exc.code, message=exc.message, retryable=exc.retryable)
    except Exception:
        logger.exception("unexpected agent error", extra={**ctx, "event": "agent_crashed"})
        error = AgentError(code=ErrorCode.INTERNAL_ERROR, message=f"Unexpected {agent_name} error",
                           retryable=False)

    return AgentResult[output_type](success=False, agent=agent_name, error=error, meta=meta())
