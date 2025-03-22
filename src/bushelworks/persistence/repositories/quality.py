"""The complaint repository."""

from __future__ import annotations

from datetime import date
from decimal import Decimal

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from ...errors import ConflictError, LookupFailure
from ..base import as_decimal
from ..models import ComplaintActionRow, ComplaintRow


class ComplaintNotFound(LookupFailure):
    code = "complaint_not_found"


class ComplaintRepository:
    """The complaint register.

    Rows in and rows out rather than domain objects. The domain register is an
    in memory list that answers questions about a whole season, and building it
    per call would read every complaint the house has ever taken to answer one
    about a bin. The service layer builds the domain object when it wants the
    season and works off rows when it wants one.
    """

    def __init__(self, session: Session) -> None:
        self.session = session

    def row(self, reference: str) -> ComplaintRow:
        found = self.session.scalar(
            select(ComplaintRow).where(ComplaintRow.reference == reference)
        )
        if found is None:
            raise ComplaintNotFound(f"no complaint {reference}")
        return found

    def add(self, row: ComplaintRow) -> ComplaintRow:
        existing = self.session.scalar(
            select(ComplaintRow).where(ComplaintRow.reference == row.reference)
        )
        if existing is not None:
            raise ConflictError(f"{row.reference} is already on the register")
        self.session.add(row)
        self.session.flush()
        return row

    def open_complaints(self, location_bin: str | None = None) -> list[ComplaintRow]:
        query = select(ComplaintRow).where(ComplaintRow.outcome == "open")
        if location_bin:
            query = query.where(ComplaintRow.bin_id == location_bin)
        return list(
            self.session.scalars(
                query.order_by(ComplaintRow.raised_on, ComplaintRow.reference)
            )
        )

    def between(self, opens: date, closes: date) -> list[ComplaintRow]:
        return list(
            self.session.scalars(
                select(ComplaintRow)
                .where(
                    ComplaintRow.raised_on >= opens,
                    ComplaintRow.raised_on <= closes,
                )
                .order_by(ComplaintRow.raised_on, ComplaintRow.reference)
            )
        )

    def for_party(self, party_code: str) -> list[ComplaintRow]:
        return list(
            self.session.scalars(
                select(ComplaintRow)
                .where(ComplaintRow.party_code == party_code)
                .order_by(ComplaintRow.raised_on)
            )
        )

    def bin_counts(self, opens: date, closes: date) -> dict[str, int]:
        """How many complaints each bin has attracted in a span."""
        rows = self.session.execute(
            select(ComplaintRow.bin_id, func.count())
            .where(
                ComplaintRow.bin_id.is_not(None),
                ComplaintRow.raised_on >= opens,
                ComplaintRow.raised_on <= closes,
            )
            .group_by(ComplaintRow.bin_id)
        )
        return {str(bin_id): int(count) for bin_id, count in rows}

    def act(
        self, reference: str, taken_on: date, taken_by: str, what: str
    ) -> ComplaintActionRow:
        """Add a line to what was done, numbered after the last one."""
        row = self.row(reference)
        action = ComplaintActionRow(
            complaint_id=row.id,
            sequence=len(row.actions) + 1,
            taken_on=taken_on,
            taken_by=taken_by,
            what=what,
        )
        self.session.add(action)
        self.session.flush()
        return action

    def credits_between(self, opens: date, closes: date) -> Decimal:
        """What the house has paid out to settle complaints in a span."""
        total = self.session.scalar(
            select(func.sum(ComplaintRow.credit)).where(
                ComplaintRow.closed_on >= opens,
                ComplaintRow.closed_on <= closes,
            )
        )
        return as_decimal(total)
