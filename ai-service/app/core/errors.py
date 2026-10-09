from enum import Enum


class ErrorCode(str, Enum):
    INVALID_INPUT = "INVALID_INPUT"
    INSUFFICIENT_INPUT = "INSUFFICIENT_INPUT"      # input valid but not enough to work with
    LLM_TIMEOUT = "LLM_TIMEOUT"
    LLM_API_ERROR = "LLM_API_ERROR"
    MALFORMED_LLM_OUTPUT = "MALFORMED_LLM_OUTPUT"
    DOCUMENT_UNREADABLE = "DOCUMENT_UNREADABLE"
    SEARCH_FAILED = "SEARCH_FAILED"
    RESEARCH_FAILED = "RESEARCH_FAILED"
    INTERNAL_ERROR = "INTERNAL_ERROR"


class AgentException(Exception):
    """Raised inside agents; converted into an AgentResult error envelope at the boundary."""

    def __init__(self, code: ErrorCode, message: str, retryable: bool = False,
                 retry_after_seconds: float | None = None, fallback_eligible: bool | None = None):
        super().__init__(message)
        self.code = code
        self.message = message
        self.retryable = retryable
        self.retry_after_seconds = retry_after_seconds  # provider-requested wait (e.g. HTTP 429)
        # Whether a fallback model may be tried (provider outage, unknown model) — never for bad input.
        self.fallback_eligible = retryable if fallback_eligible is None else fallback_eligible
