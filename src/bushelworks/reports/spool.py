"""Getting the printed documents to the people they belong to.

Three ways out of the office and the house uses all of them in the same week:

* the dot matrix in the scale house, which takes plain text and a form feed
* the laser in the office, which takes the same text and wants page breaks at
  sixty six lines
* a file per producer, which the office attaches to an email by hand

The spool builds all three from the same rendered text. Nothing here sends
anything; it prepares files and hands back their names, because a run that also
sends cannot be tested and cannot be re run after a paper jam.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path

from ..errors import ValidationError

FORM_FEED = "\f"
PAGE_LINES = 66
SAFE_NAME = re.compile(r"[^A-Za-z0-9._-]+")


class SpoolError(ValidationError):
    """Raised when a document cannot be spooled."""

    code = "spool_error"


@dataclass(frozen=True, slots=True)
class Document:
    """One thing to print, with who it belongs to."""

    name: str
    party_code: str
    text: str

    def __post_init__(self) -> None:
        if not self.name:
            raise SpoolError("a document needs a name")
        if not self.text.strip():
            raise SpoolError(f"{self.name}: nothing to print")

    @property
    def lines(self) -> int:
        return len(self.text.split("\n"))

    @property
    def pages(self) -> int:
        return max(1, -(-self.lines // PAGE_LINES))


@dataclass
class Spool:
    """A run of documents for one day."""

    day: date
    documents: list[Document] = field(default_factory=list)

    def add(self, document: Document) -> None:
        if any(item.name == document.name for item in self.documents):
            raise SpoolError(f"{document.name} is already in the spool")
        self.documents.append(document)

    @property
    def count(self) -> int:
        return len(self.documents)

    @property
    def total_pages(self) -> int:
        return sum(document.pages for document in self.documents)

    def for_party(self, party_code: str) -> list[Document]:
        return [item for item in self.documents if item.party_code == party_code]

    def parties(self) -> tuple[str, ...]:
        return tuple(sorted({item.party_code for item in self.documents}))


def safe_filename(name: str, suffix: str = ".txt") -> str:
    """A name a file system will take, from a document number.

    Statement numbers have hyphens and nothing else, but a producer code can
    have anything in it that somebody typed at the window in 1998.
    """
    cleaned = SAFE_NAME.sub("-", name).strip("-")
    if not cleaned:
        raise SpoolError(f"{name!r} leaves nothing to name a file after")
    return f"{cleaned}{suffix}"


def to_matrix(spool: Spool) -> str:
    """One stream for the dot matrix, form feed between documents."""
    if not spool.documents:
        raise SpoolError("nothing to print")
    return FORM_FEED.join(document.text for document in spool.documents)


def to_pages(spool: Spool) -> str:
    """The same stream padded so each document starts on a fresh page."""
    if not spool.documents:
        raise SpoolError("nothing to print")
    out: list[str] = []
    for document in spool.documents:
        lines = document.text.split("\n")
        padding = (-len(lines)) % PAGE_LINES
        out.append("\n".join(lines + [""] * padding))
    return FORM_FEED.join(out)


def write_files(spool: Spool, directory: Path) -> list[Path]:
    """One file per document, named after it, under a directory per day."""
    if not spool.documents:
        raise SpoolError("nothing to write")
    target = Path(directory) / spool.day.isoformat()
    target.mkdir(parents=True, exist_ok=True)
    written: list[Path] = []
    for document in spool.documents:
        path = target / safe_filename(document.name)
        path.write_text(document.text, encoding="utf-8", newline="\n")
        written.append(path)
    return written


def manifest(spool: Spool) -> str:
    """What went out, for the office to tick off against the envelopes."""
    lines = [
        f"SPOOL {spool.day.isoformat()}",
        f"{spool.count} documents, {spool.total_pages} pages",
        "-" * 60,
    ]
    for document in sorted(spool.documents, key=lambda item: item.name):
        lines.append(
            f"{document.name:<24}{document.party_code:<20}{document.pages:>4} pages"
        )
    return "\n".join(lines)
