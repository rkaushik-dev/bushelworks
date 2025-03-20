"""Repositories.

Each one owns one aggregate and hands back domain objects rather than rows. The
query methods are named after what the office asks for rather than after the
columns they filter on, which is why there is a ``tickets_awaiting_settlement``
and not a ``find(settled=False)``.

None of them commit. Committing is the unit of work's job, and a repository that
commits on its own makes a two step operation impossible to undo.
"""

from __future__ import annotations

from collections.abc import Sequence
from datetime import date
from decimal import Decimal

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from ..domain.contracts import ContractStatus, PurchaseContract
from ..domain.settlement import Settlement
from ..domain.tickets import ScaleTicket
from ..errors import ConflictError, LookupFailure
from .base import as_decimal
from .mappers import (
    contract_from_row,
    contract_to_row,
    settlement_from_row,
    settlement_to_row,
    ticket_from_row,
    ticket_to_row,
)
from .models import (
    ApplicationRow,
    ComplaintActionRow,
    ComplaintRow,
    ContractRow,
    Location,
    Party,
    ScaleTicketRow,
    SettlementRow,
    SettlementTicketRow,
)


class TicketNotFound(LookupFailure):
    code = "ticket_not_found"


class ContractNotFound(LookupFailure):
    code = "contract_not_found"


class SettlementNotFound(LookupFailure):
    code = "settlement_not_found"


class PartyNotFound(LookupFailure):
    code = "party_not_found"


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


class ContractRepository:
    def __init__(self, session: Session) -> None:
        self.session = session

    def row(self, number: str) -> ContractRow:
        found = self.session.scalar(
            select(ContractRow).where(ContractRow.number == number)
        )
        if found is None:
            raise ContractNotFound(f"no contract {number}")
        return found

    def get(self, number: str) -> PurchaseContract:
        return contract_from_row(self.row(number))

    def add(self, contract: PurchaseContract) -> ContractRow:
        existing = self.session.scalar(
            select(ContractRow).where(ContractRow.number == contract.number)
        )
        if existing is not None:
            raise ConflictError(f"contract {contract.number} already exists")
        row = contract_to_row(contract)
        self.session.add(row)
        self.session.flush()
        return row

    def replace(self, contract: PurchaseContract) -> ContractRow:
        """Write an amended contract back over its row.

        Contracts are amended rather than voided, because a hedge to arrive that
        has had its basis set is the same contract, and the futures position is
        booked against its number.
        """
        row = self.row(contract.number)
        updated = contract_to_row(contract)
        for column in [c.name for c in ContractRow.__table__.columns]:
            # The bookkeeping columns belong to the row, not to the contract.
            # Copying them across would blank updated_at, which is set by the
            # ORM on flush and is null on an object that has never been added.
            if column in ("id", "created_at", "updated_at"):
                continue
            setattr(row, column, getattr(updated, column))
        self.session.flush()
        return row

    def open_for(
        self, party_code: str, commodity_code: str, day: date
    ) -> list[PurchaseContract]:
        rows = self.session.scalars(
            select(ContractRow)
            .where(
                ContractRow.party_code == party_code,
                ContractRow.commodity_code == commodity_code,
                ContractRow.status == ContractStatus.OPEN.value,
                ContractRow.window_opens <= day,
                ContractRow.window_closes >= day,
            )
            .order_by(ContractRow.window_closes, ContractRow.number)
        )
        return [contract_from_row(row) for row in rows]

    def applied_bushels(self, number: str) -> Decimal:
        row = self.row(number)
        total = self.session.scalar(
            select(func.sum(ApplicationRow.bushels)).where(
                ApplicationRow.contract_id == row.id,
                ApplicationRow.reversed_on.is_(None),
            )
        )
        return as_decimal(total)

    def close(self, number: str) -> ContractRow:
        row = self.row(number)
        if row.status != ContractStatus.OPEN.value:
            raise ConflictError(f"contract {number} is {row.status}, not open")
        row.status = ContractStatus.FILLED.value
        self.session.flush()
        return row


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


class PartyRepository:
    def __init__(self, session: Session) -> None:
        self.session = session

    def get(self, code: str) -> Party:
        found = self.session.scalar(select(Party).where(Party.code == code))
        if found is None:
            raise PartyNotFound(f"no party {code}")
        return found

    def active(self) -> list[Party]:
        return list(
            self.session.scalars(
                select(Party).where(Party.is_active.is_(True)).order_by(Party.sort_name)
            )
        )

    def locations(self) -> list[Location]:
        return list(
            self.session.scalars(
                select(Location).where(Location.is_active.is_(True)).order_by(Location.code)
            )
        )


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
