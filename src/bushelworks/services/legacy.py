"""Reading the old system's ticket file.

The system this replaced wrote a fixed width file every night and the house has
eleven years of them. They are the only record of anything before go live, so
they have to come in, and they come in dirty:

* weights are in the file as text with leading spaces and the occasional comma
* moisture is sometimes blank, which the old system meant as "not tested" and
  sometimes meant as zero
* a void is a flag in column 78 and the original row is still there
* the party code is the old six character one, which has to be mapped

Nothing here writes to the database. It reads rows, reports what it cannot read,
and hands back what it could. A migration that silently drops the rows it does
not understand is worse than one that fails.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import date, datetime
from decimal import Decimal, InvalidOperation

from ..domain.commodity import Factor
from ..domain.rulebook import RuleBook
from ..domain.tickets import Disposition, ScaleTicket, SplitShare
from ..errors import BushelworksError, ValidationError
from ..persistence.unitofwork import UnitOfWork
from .scalehouse import record_load

# Column offsets in the old file, zero based and half open.
LAYOUT: dict[str, tuple[int, int]] = {
    "ticket_number": (0, 10),
    "scale_date": (10, 18),
    "party_code": (18, 24),
    "commodity_code": (24, 28),
    "location_code": (28, 32),
    "gross_pounds": (32, 42),
    "tare_pounds": (42, 52),
    "moisture": (52, 58),
    "test_weight": (58, 64),
    "damage": (64, 70),
    "disposition": (70, 78),
    "void_flag": (78, 79),
}

RECORD_LENGTH = 79

DISPOSITIONS: dict[str, str] = {
    "CASH    ": "cash_sale",
    "STORE   ": "store",
    "PRICELTR": "price_later",
    "CONTRACT": "contract",
    "FEED    ": "feed",
}


class LegacyImportError(ValidationError):
    """Raised when a row cannot be read at all."""

    code = "import_error"


@dataclass(frozen=True, slots=True)
class Row:
    """One old ticket, as far as it could be read."""

    line_number: int
    ticket_number: str
    scale_date: date
    party_code: str
    commodity_code: str
    location_code: str
    gross_pounds: Decimal
    tare_pounds: Decimal
    disposition: str
    voided: bool
    moisture: Decimal | None = None
    test_weight: Decimal | None = None
    damage: Decimal | None = None

    @property
    def net_pounds(self) -> Decimal:
        return self.gross_pounds - self.tare_pounds


@dataclass(frozen=True, slots=True)
class Rejected:
    """A row that could not be read, with why."""

    line_number: int
    raw: str
    reason: str


@dataclass
class Result:
    """What a file gave up."""

    rows: list[Row] = field(default_factory=list)
    rejected: list[Rejected] = field(default_factory=list)

    @property
    def read(self) -> int:
        return len(self.rows)

    @property
    def refused(self) -> int:
        return len(self.rejected)

    @property
    def is_clean(self) -> bool:
        return not self.rejected

    def by_party(self, party_code: str) -> list[Row]:
        return [row for row in self.rows if row.party_code == party_code]


def field_at(line: str, name: str) -> str:
    start, end = LAYOUT[name]
    return line[start:end]


def _decimal(text: str, label: str, line_number: int) -> Decimal:
    cleaned = text.replace(",", "").strip()
    if not cleaned:
        raise LegacyImportError(f"line {line_number}: {label} is blank")
    try:
        return Decimal(cleaned)
    except InvalidOperation as exc:
        raise LegacyImportError(
            f"line {line_number}: {label} is {text.strip()!r}"
        ) from exc


def _optional_decimal(text: str) -> Decimal | None:
    """Blank means not tested, which is not the same as zero.

    The old system used the same blank for both and there is no way to tell
    them apart, so everything blank comes across as not tested. That loses a
    genuine zero reading and it is the safe direction: a missing factor cannot
    be graded, and a false zero would grade as perfect.
    """
    cleaned = text.replace(",", "").strip()
    if not cleaned:
        return None
    try:
        return Decimal(cleaned)
    except InvalidOperation:
        return None


def parse_line(line: str, line_number: int, party_map: dict[str, str] | None = None) -> Row:
    """Read one fixed width row, or say which field beat it."""
    if len(line) < RECORD_LENGTH:
        raise LegacyImportError(
            f"line {line_number}: {len(line)} characters, expected {RECORD_LENGTH}"
        )

    ticket_number = field_at(line, "ticket_number").strip()
    if not ticket_number:
        raise LegacyImportError(f"line {line_number}: no ticket number")

    raw_date = field_at(line, "scale_date").strip()
    try:
        scale_date = datetime.strptime(raw_date, "%Y%m%d").date()
    except ValueError as exc:
        raise LegacyImportError(f"line {line_number}: {raw_date!r} is not a date") from exc

    old_code = field_at(line, "party_code").strip()
    party_code = (party_map or {}).get(old_code, old_code)
    if not party_code:
        raise LegacyImportError(f"line {line_number}: no party code")

    raw_disposition = field_at(line, "disposition")
    disposition = DISPOSITIONS.get(raw_disposition)
    if disposition is None:
        raise LegacyImportError(
            f"line {line_number}: {raw_disposition.strip()!r} is not a disposition"
        )

    gross = _decimal(field_at(line, "gross_pounds"), "gross", line_number)
    tare = _decimal(field_at(line, "tare_pounds"), "tare", line_number)
    if tare > gross:
        raise LegacyImportError(f"line {line_number}: tare {tare} exceeds gross {gross}")

    return Row(
        line_number=line_number,
        ticket_number=ticket_number,
        scale_date=scale_date,
        party_code=party_code,
        commodity_code=field_at(line, "commodity_code").strip(),
        location_code=field_at(line, "location_code").strip(),
        gross_pounds=gross,
        tare_pounds=tare,
        disposition=disposition,
        voided=field_at(line, "void_flag") == "V",
        moisture=_optional_decimal(field_at(line, "moisture")),
        test_weight=_optional_decimal(field_at(line, "test_weight")),
        damage=_optional_decimal(field_at(line, "damage")),
    )


def parse_file(text: str, party_map: dict[str, str] | None = None) -> Result:
    """Read a whole night's file, keeping what fails and why."""
    result = Result()
    for index, line in enumerate(text.splitlines(), start=1):
        if not line.strip():
            continue
        try:
            result.rows.append(parse_line(line, index, party_map))
        except LegacyImportError as error:
            result.rejected.append(Rejected(index, line, error.message))
    return result


def duplicates(result: Result) -> list[str]:
    """Ticket numbers that appear more than once at the same site.

    The old file repeats a ticket when it was corrected, and the correction is
    the later row. Nothing is dropped here; the caller decides.
    """
    seen: dict[tuple[str, str], int] = {}
    for row in result.rows:
        key = (row.location_code, row.ticket_number)
        seen[key] = seen.get(key, 0) + 1
    return sorted(
        f"{location}/{number}" for (location, number), count in seen.items() if count > 1
    )


def summary(result: Result) -> str:
    """One paragraph for the migration log."""
    if result.is_clean:
        return f"{result.read} tickets read, none refused"
    reasons = ", ".join(
        sorted({item.reason.split(":", 1)[-1].strip() for item in result.rejected})[:5]
    )
    return (
        f"{result.read} tickets read, {result.refused} refused: {reasons}"
    )


@dataclass
class Loaded:
    """What a load into the database actually did.

    Kept apart from ``Result`` because reading a file and writing it are two
    different failures. A row that parsed and then would not go in is a row the
    house has to look at by hand, and it is a different pile from the ones that
    would not parse at all.
    """

    written: list[str] = field(default_factory=list)
    skipped: list[Rejected] = field(default_factory=list)

    @property
    def count(self) -> int:
        return len(self.written)

    @property
    def is_clean(self) -> bool:
        return not self.skipped


def to_ticket(
    row: Row, assume: Mapping[Factor, Decimal] | None = None
) -> ScaleTicket:
    """Turn an old row into a ticket the rest of the system understands.

    The old file has no split table. Every ticket comes in whole to the party
    on it, and a landlord share that existed in the old system is lost, which
    the house knows and accepted: those settlements are eleven years closed.

    A blank moisture is left out of the sample rather than sent in as zero. Zero
    moisture grades as bone dry and pays a shrink of nothing, and the old system
    meant it as "we did not test this load".

    ``assume`` fills factors the old system never had a column for at all. There
    is no BCFM in that file and there never was, so a historic ticket cannot be
    graded without one. The house supplies its long run average once for the
    whole migration rather than the loader inventing a number per row, and the
    remark on every imported ticket says the sample was filled in.
    """
    sample: dict[Factor, Decimal] = dict(assume or {})
    if row.moisture is not None:
        sample[Factor.MOISTURE] = row.moisture
    if row.test_weight is not None:
        sample[Factor.TEST_WEIGHT] = row.test_weight
    if row.damage is not None:
        sample[Factor.DAMAGE_TOTAL] = row.damage
    note = f"imported from the old file, line {row.line_number}"
    if assume:
        filled = ", ".join(sorted(factor.value for factor in assume))
        note = f"{note}; {filled} assumed"
    return ScaleTicket(
        ticket_number=row.ticket_number,
        scale_date=row.scale_date,
        location_code=row.location_code,
        commodity_code=row.commodity_code,
        gross_pounds=row.gross_pounds,
        tare_pounds=row.tare_pounds,
        disposition=Disposition(row.disposition),
        splits=[SplitShare(party_id=row.party_code, percent=Decimal("100"))],
        sample=sample,
        remarks=note,
        voided=row.voided,
    )


def load(
    uow: UnitOfWork,
    result: Result,
    rules: RuleBook,
    skip_existing: bool = True,
    assume: Mapping[Factor, Decimal] | None = None,
) -> Loaded:
    """Write parsed rows into the ticket file, keeping what would not go.

    Nothing here commits. The caller decides, and the migration is run once with
    the commit turned off to see what it would do.

    A ticket already on file is skipped rather than refused. The migration gets
    run more than once, usually because it stopped halfway the first time, and
    a second pass that falls over on the rows the first one wrote is useless.

    Voided rows are always skipped, and there is no option to bring them in.
    Intake refuses a voided ticket outright, which is right: a void is a load
    that did not happen and grading one would put a discount and a shrink on
    file against grain nobody ever took. The old file keeps the void row and so
    does the old file; this database does not need a second copy of it.
    """
    loaded = Loaded()
    for row in result.rows:
        if row.voided:
            loaded.skipped.append(
                Rejected(row.line_number, row.ticket_number, "voided in the old system")
            )
            continue
        if skip_existing and uow.tickets.exists(row.location_code, row.ticket_number):
            loaded.skipped.append(
                Rejected(row.line_number, row.ticket_number, "already on file")
            )
            continue
        try:
            record_load(uow, to_ticket(row, assume), row.party_code, rules)
        except BushelworksError as error:
            loaded.skipped.append(
                Rejected(row.line_number, row.ticket_number, error.message)
            )
            continue
        loaded.written.append(row.ticket_number)
    return loaded


def load_summary(loaded: Loaded) -> str:
    """One paragraph for the migration log."""
    if loaded.is_clean:
        return f"{loaded.count} tickets written, none skipped"
    reasons = ", ".join(sorted({item.reason for item in loaded.skipped})[:5])
    return f"{loaded.count} tickets written, {len(loaded.skipped)} skipped: {reasons}"
