from typing import Generic, TypeVar

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from app.core.errors import ErrorCode

SCHEMA_VERSION = "1.0"
InputT = TypeVar("InputT")
DataT = TypeVar("DataT")


class CamelModel(BaseModel):
    """Accepts and emits camelCase JSON (for Spring Boot) while Python code uses snake_case.

    coerce_numbers_to_str: Spring Boot usually sends IDs as JSON numbers (Long), e.g. pitchId: 42.
    We accept them and treat every ID as a string internally.
    """

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, coerce_numbers_to_str=True)


class AgentRequest(CamelModel, Generic[InputT]):
    # Spring Boot generates execution_id deterministically (workflow_id:agent:step) and is
    # responsible for idempotency: if a stored result exists for this ID, don't call again.
    execution_id: str = Field(min_length=1, max_length=200)
    trace_id: str = Field(min_length=1, max_length=200)
    input: InputT


class AgentError(CamelModel):
    code: ErrorCode
    message: str
    retryable: bool


class AgentMeta(CamelModel):
    execution_id: str
    trace_id: str
    schema_version: str = SCHEMA_VERSION
    model: str | None = None
    attempts: int = 0
    prompt_tokens: int = 0
    completion_tokens: int = 0
    latency_ms: int = 0
    estimated_cost_usd: float | None = None   # from LLM_PRICES; None when the model's price is not configured
    input_hash: str | None = None             # SHA-256 of the input, for audit and cache/idempotency checks
    fallback_used: bool = False               # the fallback model answered because the primary failed


class AgentResult(CamelModel, Generic[DataT]):
    success: bool
    agent: str
    data: DataT | None = None
    error: AgentError | None = None
    meta: AgentMeta
