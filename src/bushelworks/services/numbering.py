"""Document numbers.

Ticket numbers come off pre-numbered stock and belong to the scale house, so
this module never invents one. Settlement numbers are ours, and they have to be
gapless within a year because the state auditor counts them.

Gapless is the hard part. Taking the current maximum and adding one races the
moment two clerks settle at the same time, and a sequence in the database gives
gaps whenever a transaction rolls back. What the office needs is a counter row
that is locked for the length of the transaction, so a rollback releases the
number and the next caller gets it.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date

from sqlalchemy import select
from sqlalchemy.orm import Session

from ..errors import ConflictError, ValidationError
from ..persistence.models.reference import CounterRow


class NumberingError(ValidationError):
    """Raised when a document number cannot be allocated."""

    code = "numbering_error"


@dataclass(frozen=True, slots=True)
class DocumentNumber:
    """A parsed document number, so a string can be checked rather than trusted."""

    prefix: str
    year: int
    sequence: int

    def __str__(self) -> str:
        return f"{self.prefix}-{self.year}-{self.sequence:04d}"

    @classmethod
    def parse(cls, text: str) -> DocumentNumber:
        parts = text.split("-")
        if len(parts) != 3:
            raise NumberingError(f"{text!r} is not a document number")
        prefix, year, sequence = parts
        if not prefix:
            raise NumberingError(f"{text!r} has no prefix")
        if not year.isdigit() or len(year) != 4:
            raise NumberingError(f"{text!r} has no four digit year")
        if not sequence.isdigit():
            raise NumberingError(f"{text!r} has no sequence")
        return cls(prefix=prefix, year=int(year), sequence=int(sequence))

    def next(self) -> DocumentNumber:
        return DocumentNumber(self.prefix, self.year, self.sequence + 1)


class Numbering:
    """Allocates the next number in a series, inside the caller's transaction."""

    def __init__(self, session: Session) -> None:
        self.session = session

    def _counter(self, series: str, year: int) -> CounterRow:
        row = self.session.scalar(
            select(CounterRow)
            .where(CounterRow.series == series, CounterRow.year == year)
            .with_for_update()
        )
        if row is None:
            row = CounterRow(series=series, year=year, next_value=1)
            self.session.add(row)
            self.session.flush()
        return row

    def allocate(self, series: str, on: date) -> DocumentNumber:
        """Take the next number in the series for the year ``on`` falls in."""
        if not series:
            raise NumberingError("a series needs a name")
        row = self._counter(series, on.year)
        number = DocumentNumber(prefix=series, year=on.year, sequence=row.next_value)
        row.next_value += 1
        self.session.flush()
        return number

    def peek(self, series: str, on: date) -> int:
        row = self.session.scalar(
            select(CounterRow).where(
                CounterRow.series == series, CounterRow.year == on.year
            )
        )
        return 1 if row is None else row.next_value

    def reserve_block(self, series: str, on: date, count: int) -> list[DocumentNumber]:
        """Take a run of numbers at once, for a batch settlement run."""
        if count < 1:
            raise NumberingError("a block has to be at least one number")
        row = self._counter(series, on.year)
        start = row.next_value
        row.next_value += count
        self.session.flush()
        return [
            DocumentNumber(prefix=series, year=on.year, sequence=start + offset)
            for offset in range(count)
        ]

    def check_gapless(self, series: str, year: int, used: list[int]) -> None:
        """Assert a year's issued numbers have no holes in them.

        Run at year end against the settlement table. A hole means a number was
        allocated and the settlement was never written, which is exactly what
        the auditor wants explained.
        """
        if not used:
            return
        expected = list(range(1, max(used) + 1))
        missing = sorted(set(expected) - set(used))
        if missing:
            listed = ", ".join(str(m) for m in missing[:10])
            raise ConflictError(
                f"{series} {year} is missing {len(missing)} numbers: {listed}"
            )
