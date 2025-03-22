"""The ticket repository.

A ticket is only unique inside one elevator, which is why nearly every method
here takes a location as well as a number. The one that does not is
``by_number``, and it hands back a list for exactly that reason.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from ...domain.tickets import ScaleTicket
from ...errors import ConflictError, LookupFailure
from ..base import as_decimal
from ..mappers import ticket_from_row, ticket_to_row
from ..models import ScaleTicketRow


class TicketNotFound(LookupFailure):
    code = "ticket_not_found"


class TicketRepository:
    """Scale tickets. Append only, keyed by location and ticket number."""

    def __init__(self, session: Session) -> None:
        self.session = session

    def row(self, location_code: str, ticket_number: str) -> ScaleTicketRow:
        found = self.session.scalar(
            select(ScaleTicketRow).where(
                ScaleTicketRow.location_code == location_code,
                ScaleTicketRow.ticket_number == ticket_number,
            )
        )
        if found is None:
            raise TicketNotFound(f"no ticket {ticket_number} at {location_code}")
        return found

    def get(self, location_code: str, ticket_number: str) -> ScaleTicket:
        return ticket_from_row(self.row(location_code, ticket_number))

    def exists(self, location_code: str, ticket_number: str) -> bool:
        count = self.session.scalar(
            select(func.count())
            .select_from(ScaleTicketRow)
            .where(
                ScaleTicketRow.location_code == location_code,
                ScaleTicketRow.ticket_number == ticket_number,
            )
        )
        return bool(count)

    def add(self, ticket: ScaleTicket, party_code: str) -> ScaleTicketRow:
        if self.exists(ticket.location_code, ticket.ticket_number):
            raise ConflictError(
                f"ticket {ticket.ticket_number} already written at "
                f"{ticket.location_code}"
            )
        row = ticket_to_row(ticket, party_code)
        self.session.add(row)
        self.session.flush()
        return row

    def void(self, location_code: str, ticket_number: str, reason: str) -> ScaleTicketRow:
        row = self.row(location_code, ticket_number)
        if row.voided:
            raise ConflictError(f"ticket {ticket_number} is already voided")
        if row.settled:
            raise ConflictError(f"ticket {ticket_number} has already been settled")
        row.voided = True
        row.remarks = f"VOID: {reason}"
        self.session.flush()
        return row

    def by_number(self, ticket_number: str) -> list[ScaleTicketRow]:
        """Every ticket carrying a number, across the sites.

        A number is only unique inside one elevator, so this is a list. It
        exists because a complaint arrives with a number read out over the
        telephone and nobody says which house it was written at.
        """
        return list(
            self.session.scalars(
                select(ScaleTicketRow)
                .where(ScaleTicketRow.ticket_number == ticket_number)
                .order_by(ScaleTicketRow.scale_date, ScaleTicketRow.id)
            )
        )

    def for_party(
        self, party_code: str, since: date | None = None
    ) -> list[ScaleTicketRow]:
        query = select(ScaleTicketRow).where(ScaleTicketRow.party_code == party_code)
        if since is not None:
            query = query.where(ScaleTicketRow.scale_date >= since)
        return list(
            self.session.scalars(query.order_by(ScaleTicketRow.scale_date, ScaleTicketRow.id))
        )

    def awaiting_settlement(
        self, location_code: str | None = None
    ) -> list[ScaleTicketRow]:
        query = select(ScaleTicketRow).where(
            ScaleTicketRow.settled.is_(False),
            ScaleTicketRow.voided.is_(False),
        )
        if location_code is not None:
            query = query.where(ScaleTicketRow.location_code == location_code)
        return list(self.session.scalars(query.order_by(ScaleTicketRow.scale_date)))

    def bushels_received(
        self, commodity_code: str, start: date, end: date
    ) -> Decimal:
        """Net pounds over a span, converted by the caller. Voids excluded."""
        total = self.session.scalar(
            select(func.sum(ScaleTicketRow.gross_pounds - ScaleTicketRow.tare_pounds))
            .where(
                ScaleTicketRow.commodity_code == commodity_code,
                ScaleTicketRow.scale_date >= start,
                ScaleTicketRow.scale_date <= end,
                ScaleTicketRow.voided.is_(False),
            )
        )
        return as_decimal(total)
