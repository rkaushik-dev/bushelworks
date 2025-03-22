"""The settlement repository.

A settlement is written once and then only ever marked paid. There is no
``replace`` here on purpose: a statement that went out in the post is not a row
to be edited, and a wrong one is corrected by voiding it and writing another.
"""

from __future__ import annotations

from collections.abc import Sequence
from datetime import date

from sqlalchemy import select
from sqlalchemy.orm import Session

from ...domain.settlement import Settlement
from ...errors import ConflictError, LookupFailure
from ..mappers import settlement_from_row, settlement_to_row
from ..models import SettlementRow, SettlementTicketRow


class SettlementNotFound(LookupFailure):
    code = "settlement_not_found"


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
