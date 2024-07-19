"""Logging that a grain accountant can grep.

One line per event, key equals value, no multi line records. The office box has
no log aggregator and nobody is going to install one, so the format has to be
readable in ``less`` and greppable in a batch file. That rules out JSON, which
is unreadable at the terminal, and it rules out free text, which is ungreppable.

Two rules are enforced rather than suggested:

* every quantity is rendered with ``str`` on a Decimal, never with ``%f``
* a value containing a space is quoted, so ``grep 'party=MERRICK'`` cannot match
  the middle of somebody's name
"""

from __future__ import annotations

import logging
import sys
from contextvars import ContextVar
from decimal import Decimal
from typing import Any, Iterator, Mapping

LOGGER_NAME = "bushelworks"

# Set by the API middleware and by the CLI, read by the formatter. A context
# variable rather than a thread local because the API is async and a thread
# local leaks the wrong id across awaits.
_request_id: ContextVar[str | None] = ContextVar("request_id", default=None)


def set_request_id(value: str | None) -> None:
    _request_id.set(value)


def current_request_id() -> str | None:
    return _request_id.get()


def render_value(value: Any) -> str:
    if value is None:
        return "-"
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, bool):
        return "yes" if value else "no"
    text = str(value)
    if not text:
        return '""'
    if any(char in text for char in " \t=\"'"):
        escaped = text.replace('"', '\\"')
        return f'"{escaped}"'
    return text


def render_fields(fields: Mapping[str, Any]) -> str:
    return " ".join(f"{key}={render_value(value)}" for key, value in fields.items())


class KeyValueFormatter(logging.Formatter):
    """Timestamp, level, event, then the fields."""

    def format(self, record: logging.LogRecord) -> str:
        parts = [
            self.formatTime(record, "%Y-%m-%dT%H:%M:%S"),
            record.levelname.lower(),
            record.getMessage(),
        ]
        request_id = getattr(record, "request_id", None) or current_request_id()
        fields: dict[str, Any] = {}
        if request_id:
            fields["request_id"] = request_id
        extra = getattr(record, "fields", None)
        if isinstance(extra, Mapping):
            fields.update(extra)
        if fields:
            parts.append(render_fields(fields))
        line = " ".join(parts)
        if record.exc_info:
            line = f"{line} error={render_value(self.formatException(record.exc_info))}"
        return line


def configure(level: str = "INFO", stream: Any = None) -> logging.Logger:
    """Set the package logger up once. Safe to call twice."""
    logger = logging.getLogger(LOGGER_NAME)
    logger.setLevel(level.upper())
    logger.propagate = False
    for handler in list(logger.handlers):
        logger.removeHandler(handler)
    handler = logging.StreamHandler(stream or sys.stderr)
    handler.setFormatter(KeyValueFormatter())
    logger.addHandler(handler)
    return logger


def get_logger(name: str | None = None) -> logging.Logger:
    if name is None:
        return logging.getLogger(LOGGER_NAME)
    return logging.getLogger(f"{LOGGER_NAME}.{name}")


def event(logger: logging.Logger, name: str, **fields: Any) -> None:
    """Log one named event with its fields.

    The event name is the thing to grep for and it never changes. Fields come
    and go; ``ticket.recorded`` does not.
    """
    logger.info(name, extra={"fields": fields})


def warn(logger: logging.Logger, name: str, **fields: Any) -> None:
    logger.warning(name, extra={"fields": fields})


def failure(logger: logging.Logger, name: str, **fields: Any) -> None:
    logger.error(name, extra={"fields": fields})


class capture:
    """Collect log lines in a list. Used by the tests and by nothing else."""

    def __init__(self, level: str = "INFO") -> None:
        self.level = level
        self.lines: list[str] = []
        self._handler: logging.Handler | None = None

    def __enter__(self) -> "capture":
        logger = get_logger()
        handler = _ListHandler(self.lines)
        handler.setFormatter(KeyValueFormatter())
        logger.setLevel(self.level.upper())
        logger.addHandler(handler)
        self._handler = handler
        return self

    def __exit__(self, *exc: object) -> None:
        if self._handler is not None:
            get_logger().removeHandler(self._handler)
        self._handler = None

    def __iter__(self) -> Iterator[str]:
        return iter(self.lines)

    def find(self, event_name: str) -> list[str]:
        return [line for line in self.lines if f" {event_name} " in f" {line} "]


class _ListHandler(logging.Handler):
    def __init__(self, sink: list[str]) -> None:
        super().__init__()
        self.sink = sink

    def emit(self, record: logging.LogRecord) -> None:
        self.sink.append(self.format(record))
