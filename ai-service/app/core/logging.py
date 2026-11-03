import contextvars
import json
import logging
import re
import sys

# Correlation ID of the current HTTP request (set by the request middleware).
request_id_var: contextvars.ContextVar[str | None] = contextvars.ContextVar("request_id", default=None)

# Defence in depth: anything that looks like a credential is masked even if it ends up in a message.
_SECRETS = re.compile(r"(sk-[A-Za-z0-9_-]{8,}|AIza[0-9A-Za-z_-]{20,}|tvly-[A-Za-z0-9_-]{8,}|"
                      r"(?i:bearer)\s+[A-Za-z0-9._~+/=-]{12,}|eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]*)")


def redact(text: str) -> str:
    return _SECRETS.sub("[REDACTED]", text)

# Only these extra fields are emitted. Email bodies, deck text and prompts are
# never logged — log metadata, not content.
_EXTRA_FIELDS = (
    "trace_id", "execution_id", "agent", "event", "error_code", "category", "attempt",
    # counts only — never content
    "claims", "sources", "evidence", "slots", "actions", "query_count", "round", "signals",
)


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload = {
            "ts": self.formatTime(record),
            "level": record.levelname,
            "logger": record.name,
            "msg": redact(record.getMessage()),
        }
        rid = request_id_var.get()
        if rid:
            payload["request_id"] = rid
        for key in _EXTRA_FIELDS:
            if hasattr(record, key):
                payload[key] = getattr(record, key)
        if record.exc_info:
            payload["exc"] = redact(self.formatException(record.exc_info))
        return json.dumps(payload)


def configure_logging(level: str = "INFO") -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonFormatter())
    root = logging.getLogger()
    root.handlers = [handler]
    root.setLevel(level.upper())
