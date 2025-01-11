"""Settings, read from the environment once at start up.

Everything here has a default that works on a laptop with no environment set,
except the database URL in production. The scale house runs on a box in the
office with no operations team, so a setting that has to be discovered by
reading a stack trace is a setting that will be wrong.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from decimal import Decimal
from typing import Any

from .errors import ConfigurationError

PREFIX = "BUSHELWORKS_"


def _env(name: str, default: str | None = None) -> str | None:
    return os.environ.get(f"{PREFIX}{name}", default)


def _env_bool(name: str, default: bool) -> bool:
    raw = _env(name)
    if raw is None:
        return default
    lowered = raw.strip().lower()
    if lowered in ("1", "true", "yes", "on"):
        return True
    if lowered in ("0", "false", "no", "off"):
        return False
    raise ConfigurationError(f"{PREFIX}{name} should be a yes or a no, got {raw!r}")


def _env_int(name: str, default: int) -> int:
    raw = _env(name)
    if raw is None:
        return default
    try:
        return int(raw)
    except ValueError as exc:
        raise ConfigurationError(f"{PREFIX}{name} should be a whole number") from exc


def _env_decimal(name: str, default: str) -> Decimal:
    raw = _env(name, default)
    try:
        return Decimal(raw or default)
    except Exception as exc:
        raise ConfigurationError(f"{PREFIX}{name} should be a number") from exc


# Defaults live out here rather than only on the dataclass. ``slots=True``
# replaces the class attributes with descriptors, so ``Settings.state_code``
# is not the string "KS" and cannot be read as a fallback.
DEFAULT_DATABASE_URL = "sqlite+pysqlite:///bushelworks.sqlite3"
DEFAULT_HOUSE_NAME = "BUSHELWORKS ELEVATOR"
DEFAULT_STATE_CODE = "KS"
DEFAULT_LOCATION = "HOME"
DEFAULT_STATEMENT_PREFIX = "S"
DEFAULT_TICKET_PREFIX = "A"
DEFAULT_MAX_PAGE_SIZE = 200
DEFAULT_LOG_LEVEL = "INFO"
# Calls a minute per caller. Zero turns the limit off, which is what a house
# with only a scale house terminal and an office wants.
DEFAULT_RATE_LIMIT = 0
LOG_LEVELS = ("DEBUG", "INFO", "WARNING", "ERROR")


@dataclass(frozen=True, slots=True)
class Settings:
    """Everything the application needs to start."""

    database_url: str = DEFAULT_DATABASE_URL
    echo_sql: bool = False
    house_name: str = DEFAULT_HOUSE_NAME
    state_code: str = DEFAULT_STATE_CODE
    default_location: str = DEFAULT_LOCATION
    statement_prefix: str = DEFAULT_STATEMENT_PREFIX
    ticket_prefix: str = DEFAULT_TICKET_PREFIX
    max_page_size: int = DEFAULT_MAX_PAGE_SIZE
    log_level: str = DEFAULT_LOG_LEVEL
    rate_limit_per_minute: int = DEFAULT_RATE_LIMIT
    api_keys: frozenset[str] = field(default_factory=frozenset)
    minimum_settlement: Decimal = Decimal("0.00")

    def __post_init__(self) -> None:
        if not self.database_url:
            raise ConfigurationError("a database url is required")
        if self.log_level not in LOG_LEVELS:
            listed = ", ".join(LOG_LEVELS)
            raise ConfigurationError(
                f"log level {self.log_level!r} should be one of {listed}"
            )
        if len(self.state_code) != 2:
            raise ConfigurationError(
                f"state code {self.state_code!r} should be two letters"
            )
        if self.rate_limit_per_minute < 0:
            raise ConfigurationError("a rate limit cannot be negative")
        if self.max_page_size < 1:
            raise ConfigurationError("max page size has to be at least one")
        if self.minimum_settlement < 0:
            raise ConfigurationError("minimum settlement cannot be negative")

    @property
    def is_sqlite(self) -> bool:
        return self.database_url.startswith("sqlite")

    @property
    def requires_api_key(self) -> bool:
        return bool(self.api_keys)

    @property
    def key_roles(self) -> dict[str, Any]:
        """Each configured key mapped to the role it carries.

        Parsed on demand rather than at load time so the settings object stays
        a plain frozen dataclass and the security module owns the format.
        """
        from .api.security import parse_keys

        return parse_keys(",".join(sorted(self.api_keys)))


def load_settings() -> Settings:
    """Read the environment into a Settings, raising on anything unusable."""
    keys = _env("API_KEYS", "") or ""
    return Settings(
        database_url=_env("DATABASE_URL") or DEFAULT_DATABASE_URL,
        echo_sql=_env_bool("ECHO_SQL", False),
        house_name=_env("HOUSE_NAME") or DEFAULT_HOUSE_NAME,
        state_code=(_env("STATE_CODE") or DEFAULT_STATE_CODE).upper(),
        default_location=_env("DEFAULT_LOCATION") or DEFAULT_LOCATION,
        statement_prefix=_env("STATEMENT_PREFIX") or DEFAULT_STATEMENT_PREFIX,
        ticket_prefix=_env("TICKET_PREFIX") or DEFAULT_TICKET_PREFIX,
        max_page_size=_env_int("MAX_PAGE_SIZE", DEFAULT_MAX_PAGE_SIZE),
        log_level=(_env("LOG_LEVEL") or DEFAULT_LOG_LEVEL).upper(),
        rate_limit_per_minute=_env_int("RATE_LIMIT_PER_MINUTE", DEFAULT_RATE_LIMIT),
        api_keys=frozenset(k.strip() for k in keys.split(",") if k.strip()),
        minimum_settlement=_env_decimal("MINIMUM_SETTLEMENT", "0.00"),
    )
