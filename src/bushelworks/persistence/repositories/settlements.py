"""The settlement repository.

A settlement is written once and then only ever marked paid. There is no
``replace`` here on purpose: a statement that went out in the post is not a row
to be edited, and a wrong one is corrected by voiding it and writing another.

``in_window``, ``line_totals`` and ``ticket_counts`` are here rather than in the
services that use them because the tax file and the patronage table were each
carrying a private copy. They agreed, which is the dangerous kind of duplication:
the two reports are checked against each other at year end, and two copies of the
same query cannot disagree even when one of them is wrong.

``ticket_counts`` also fixes what the copies were doing badly. Counting loads a
statement at a time is one query per statement, and the patronage table for a
full crop year runs about four thousand of them.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from datetime import date
from decimal import Decimal

from sqlalchemy import func, select
from sqlalchemy.orm import Session, lazyload

from ...domain.settlement import Settlement
from ...errors import ConflictError, LookupFailure
from ..base import as_decimal
from ..mappers import settlement_from_row, settlement_to_row
from ..models import SettlementLineRow, SettlementRow, SettlementTicketRow


class SettlementNotFound(LookupFailure):
    code = "settlement_not_found"


@dataclass(frozen=True, slots=True)
class LineTotal:
    """What one statement carries of one kind of line.

    ``quantity`` is only meaningful on the gross lines, where it is bushels.
    A discount line has a quantity too, but it is the bushels the rate was
    applied to rather than bushels delivered, so adding it up gives a number
    that looks like volume and is not.
    """

    settlement_id: int
    kind: str
    dollars: Decimal
    quantity: Decimal


class SettlementRepository:
    def __init__(self, session: Session) -> None:
        self.session = session

    def row(self, number: str) -> SettlementRow:
        found = self.session.scalar(
            select(SettlementRow).where(SettlementRow.number == number)
        )
        if found is None:
            raise SettlementNotFound(f"no settlement {number}")
        return found

    def get(self, number: str) -> Settlement:
        return settlement_from_row(self.row(number))

    def add(self, settlement: Settlement, ticket_ids: Sequence[int] = ()) -> SettlementRow:
        existing = self.session.scalar(
            select(SettlementRow).where(SettlementRow.number == settlement.number)
        )
        if existing is not None:
            raise ConflictError(f"settlement {settlement.number} already exists")
        row = settlement_to_row(settlement)
        self.session.add(row)
        self.session.flush()
        for ticket_id in ticket_ids:
            self.session.add(
                SettlementTicketRow(settlement_id=row.id, ticket_id=ticket_id)
            )
        self.session.flush()
        return row

    def for_day(self, day: date, location_code: str | None = None) -> list[SettlementRow]:
        query = select(SettlementRow).where(SettlementRow.settled_on == day)
        if location_code is not None:
            query = query.where(SettlementRow.location_code == location_code)
        return list(self.session.scalars(query.order_by(SettlementRow.number)))

    def in_window(
        self,
        opens: date,
        closes: date,
        party_code: str | None = None,
        location_code: str | None = None,
    ) -> list[SettlementRow]:
        """Live settlements dated in a span, narrowed by party or site.

        Voided statements are never in here. A void is not a negative, it is a
        statement that never happened, and every caller of this wants it gone.

        The lines are left unloaded. They are ``selectin`` on the mapping
        because reading one statement almost always means reading its lines,
        but the callers of this want a total rather than the rows, and pulling
        four thousand statements' worth of lines to add up a column is the
        expensive half of the report.
        """
        query = (
            select(SettlementRow)
            .where(
                SettlementRow.settled_on >= opens,
                SettlementRow.settled_on <= closes,
                SettlementRow.voided.is_(False),
            )
            .options(lazyload(SettlementRow.lines))
        )
        if party_code is not None:
            query = query.where(SettlementRow.party_code == party_code)
        if location_code is not None:
            query = query.where(SettlementRow.location_code == location_code)
        return list(self.session.scalars(query.order_by(SettlementRow.number)))

    def line_totals(self, settlement_ids: Sequence[int]) -> list[LineTotal]:
        """One row per statement and kind, summed in the database."""
        if not settlement_ids:
            return []
        rows = self.session.execute(
            select(
                SettlementLineRow.settlement_id,
                SettlementLineRow.kind,
                func.sum(SettlementLineRow.dollars),
                func.sum(SettlementLineRow.quantity),
            )
            .where(SettlementLineRow.settlement_id.in_(settlement_ids))
            .group_by(SettlementLineRow.settlement_id, SettlementLineRow.kind)
        )
        return [
            LineTotal(
                settlement_id=settlement_id,
                kind=kind,
                dollars=as_decimal(dollars),
                quantity=as_decimal(quantity),
            )
            for settlement_id, kind, dollars, quantity in rows
        ]

    def totals_by_kind(self, settlement_ids: Sequence[int]) -> dict[str, Decimal]:
        """Dollars by line kind across a set of statements."""
        totals: dict[str, Decimal] = {}
        for line in self.line_totals(settlement_ids):
            totals[line.kind] = totals.get(line.kind, Decimal("0")) + line.dollars
        return totals

    def ticket_counts(self, settlement_ids: Sequence[int]) -> dict[int, int]:
        """Loads on each statement, in one query rather than one each.

        Counted off the join table, not the line count: a statement with
        fourteen tickets on it prints one gross line.
        """
        if not settlement_ids:
            return {}
        rows = self.session.execute(
            select(
                SettlementTicketRow.settlement_id,
                func.count(),
            )
            .where(SettlementTicketRow.settlement_id.in_(settlement_ids))
            .group_by(SettlementTicketRow.settlement_id)
        )
        found = {settlement_id: int(count) for settlement_id, count in rows}
        return {settlement_id: found.get(settlement_id, 0) for settlement_id in settlement_ids}

    def unpaid(self) -> list[SettlementRow]:
        return list(
            self.session.scalars(
                select(SettlementRow)
                .where(
                    SettlementRow.paid_on.is_(None),
                    SettlementRow.voided.is_(False),
                )
                .order_by(SettlementRow.settled_on, SettlementRow.number)
            )
        )

    def mark_paid(self, number: str, cheque_number: str, paid_on: date) -> SettlementRow:
        row = self.row(number)
        if row.paid_on is not None:
            raise ConflictError(
                f"settlement {number} was already paid on {row.paid_on} "
                f"by cheque {row.cheque_number}"
            )
        row.paid_on = paid_on
        row.cheque_number = cheque_number
        self.session.flush()
        return row
