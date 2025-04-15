"""Writing what the old file gave up into the ticket file.

Reading a file and writing it are two different failures and they used to live
in one module. A row that parsed and then would not go in is a row somebody has
to look at by hand, and it is a different pile from the ones that would not
parse at all.

Nothing here commits. The caller decides, and the migration is run once with the
commit turned off to see what it would do.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from decimal import Decimal

from ...domain.commodity import Factor
from ...domain.rulebook import RuleBook
from ...domain.tickets import Disposition, ScaleTicket, SplitShare
from ...errors import BushelworksError
from ...persistence.unitofwork import UnitOfWork
from ..scalehouse import record_load
from .parse import Rejected, Result, Row


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
