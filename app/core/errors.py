from enum import Enum


class ErrorCode(str, Enum):
    INVALID_INPUT = "INVALID_INPUT"
    LLM_TIMEOUT = "LLM_TIMEOUT"
    LLM_API_ERROR = "LLM_API_ERROR"
    MALFORMED_LLM_OUTPUT = "MALFORMED_LLM_OUTPUT"
    INTERNAL_ERROR = "INTERNAL_ERROR"


class AgentException(Exception):
    """Raised inside agents; converted into an AgentResult error envelope at the boundary."""

    def __init__(self, code: ErrorCode, message: str, retryable: bool = False):
        super().__init__(message)
        self.code = code
        self.message = message
        self.retryable = retryable
