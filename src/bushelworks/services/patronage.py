"""Building the patronage table out of settled statements.

Everything the board is shown comes off settlements, not off tickets. A ticket
is a weighing; a settlement is the house and the producer agreeing what the
weighing was worth, and only the second one can be added up.

Two things this deliberately does not know:

* who is a member. The membership roll is kept in the accounting package with
  the equity and the stock, and copying it into this database would give the
  house two rolls that disagree by August. It comes in as an argument.
* what a bushel of anything was worth. Gross dollars are on the settlement.

Voided settlements are dropped. A void is not a negative; it is a statement
that never happened, and netting it against a real one hides both.
"""

from __future__ import annotations

from collections.abc import Iterable, Sequence
from datetime import date
from decimal import Decimal

from sqlalchemy import func, select

from ..domain.settlement import LineKind
from ..money import amount
from ..persistence.base import as_decimal
from ..persistence.models import (
    Party,
    SettlementLineRow,
    SettlementRow,
    SettlementTicketRow,
)
from ..persistence.unitofwork import UnitOfWork
from ..reports.patronage import Line, PatronageError, Year
from ..units import BUSHEL_PLACES, quantize

# The kinds that are the house's margin rather than the producer's money.
# An assessment is collected for the checkoff board and passed straight on, and
# an advance is the producer's own money coming back, so neither is in here.
MARGIN_KINDS = (
    LineKind.DISCOUNT,
    LineKind.DRYING,
    LineKind.STORAGE,
    LineKind.SERVICE,
)


def _names(uow: UnitOfWork, codes: Iterable[str]) -> dict[str, str]:
    wanted = sorted(set(codes))
    if not wanted:
        return {}
    rows = uow.session.scalars(select(Party).where(Party.code.in_(wanted)))
    return {row.code: row.name for row in rows}


def _settlements(
    uow: UnitOfWork,
    opens: date,
    closes: date,
    location_code: str | None = None,
) -> list[SettlementRow]:
    query = select(SettlementRow).where(
        SettlementRow.settled_on >= opens,
        SettlementRow.settled_on <= closes,
        SettlementRow.voided.is_(False),
    )
    if location_code:
        query = query.where(SettlementRow.location_code == location_code)
    return list(uow.session.scalars(query))


def _ticket_count(uow: UnitOfWork, settlement_id: int) -> int:
    """Loads on one statement.

    The count is off the join table rather than the line count, because a
    statement with fourteen tickets on it prints one gross line.
    """
    return int(
        uow.session.scalar(
            select(func.count())
            .select_from(SettlementTicketRow)
            .where(SettlementTicketRow.settlement_id == settlement_id)
        )
        or 0
    )


def _totals(rows: Sequence[SettlementLineRow]) -> tuple[Decimal, Decimal]:
    """Bushels off the gross lines, margin off the charge lines."""
    bushels = Decimal("0")
    margin = Decimal("0")
    kinds = {kind.value for kind in MARGIN_KINDS}
    for row in rows:
        if row.kind == LineKind.GROSS.value and row.quantity is not None:
            bushels += as_decimal(row.quantity)
        elif row.kind in kinds:
            margin += as_decimal(row.dollars)
    return bushels, margin


def build_year(
    uow: UnitOfWork,
    opens: date,
    closes: date,
    location_code: str | None = None,
    members: Iterable[str] | None = None,
) -> Year:
    """Every producer who settled in a span, with what they were worth.

    ``members`` of ``None`` means treat everybody as a member, which is what a
    house that is not a co-op wants and what a co-op gets wrong exactly once.
    """
    if closes < opens:
        raise PatronageError("the year closes before it opens")
    roll = None if members is None else set(members)

    bushels: dict[str, Decimal] = {}
    gross: dict[str, Decimal] = {}
    charges: dict[str, Decimal] = {}
    loads: dict[str, int] = {}

    for row in _settlements(uow, opens, closes, location_code):
        code = row.party_code
        settled_bushels, margin = _totals(row.lines)
        bushels[code] = bushels.get(code, Decimal("0")) + settled_bushels
        gross[code] = gross.get(code, Decimal("0")) + as_decimal(row.gross)
        charges[code] = charges.get(code, Decimal("0")) + margin
        loads[code] = loads.get(code, 0) + _ticket_count(uow, row.id)

    names = _names(uow, bushels)
    lines = tuple(
        Line(
            party_code=code,
            name=names.get(code, code),
            bushels=quantize(bushels[code], BUSHEL_PLACES),
            gross=amount(gross[code]),
            charges=amount(charges[code]),
            is_member=roll is None or code in roll,
            loads=loads[code],
        )
        for code in sorted(bushels)
    )
    return Year(
        location_code=location_code or "*",
        opens=opens,
        closes=closes,
        lines=lines,
    )


def crop_year(year: int) -> tuple[date, date]:
    """The first of September to the last of August, which is how grain runs.

    The calendar year is the wrong span for this and using it splits one
    harvest across two tables.
    """
    return (date(year, 9, 1), date(year + 1, 8, 31))


def build_crop_year(
    uow: UnitOfWork,
    year: int,
    location_code: str | None = None,
    members: Iterable[str] | None = None,
) -> Year:
    opens, closes = crop_year(year)
    return build_year(uow, opens, closes, location_code, members)


def top_producers(year: Year, count: int = 10) -> tuple[str, ...]:
    return tuple(line.party_code for line in year.by_bushels()[:count])


def allocate(
    year: Year,
    pool: Decimal,
    minimum: Decimal = Decimal("5.00"),
) -> dict[str, Decimal]:
    """Split a pool and drop the shares not worth the postage.

    A cheque for forty cents costs more to write, post and reconcile than it is
    worth, so anything under the minimum stays in the pool. What is dropped is
    not redistributed: it goes back to unallocated reserves, which is the board's
    decision to make and not this function's.
    """
    split = year.allocations(pool)
    return {
        code: share for code, share in split.items() if share >= amount(minimum)
    }
