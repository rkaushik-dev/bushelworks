"""The migration off the old system.

``parse`` reads the fixed width file the old system wrote every night.
``load`` writes what it gave up into the ticket file. They were one module
until the loader went in and took it past three hundred and fifty lines.

Same names, same behaviour, and everything still imports from
``bushelworks.services.legacy``.
"""

from .load import Loaded, load, load_summary, to_ticket
from .parse import (
    DISPOSITIONS,
    LAYOUT,
    RECORD_LENGTH,
    LegacyImportError,
    Rejected,
    Result,
    Row,
    duplicates,
    field_at,
    parse_file,
    parse_line,
    summary,
)

__all__ = [
    "DISPOSITIONS",
    "LAYOUT",
    "RECORD_LENGTH",
    "LegacyImportError",
    "Loaded",
    "Rejected",
    "Result",
    "Row",
    "duplicates",
    "field_at",
    "load",
    "load_summary",
    "parse_file",
    "parse_line",
    "summary",
    "to_ticket",
]
