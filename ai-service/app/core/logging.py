import json
import logging
import sys

# Only these extra fields are emitted. Email bodies, deck text and prompts are
# never logged — log metadata, not content.
_EXTRA_FIELDS = (
    "trace_id", "execution_id", "agent", "event", "error_code", "category", "attempt",
    # counts only — never content
    "claims", "sources", "evidence", "slots", "actions", "query_count", "round",
)


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload = {
            "ts": self.formatTime(record),
            "level": record.levelname,
            "logger": record.name,
            "msg": record.getMessage(),
        }
        for key in _EXTRA_FIELDS:
            if hasattr(record, key):
                payload[key] = getattr(record, key)
        if record.exc_info:
            payload["exc"] = self.formatException(record.exc_info)
        return json.dumps(payload)


def configure_logging(level: str = "INFO") -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonFormatter())
    root = logging.getLogger()
    root.handlers = [handler]
    root.setLevel(level.upper())
