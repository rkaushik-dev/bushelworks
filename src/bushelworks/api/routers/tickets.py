"""Scale ticket routes.

There is no PUT and no DELETE here on purpose. A ticket is written once and
corrected by voiding, which is a POST to its own sub resource rather than a
state change on the ticket, because the void is a document in its own right.
"""

from __future__ import annotations

from datetime import date

from fastapi import APIRouter, Depends, Query, Response, status
from pydantic import Field

from ...domain.tickets import ScaleTicket, SplitShare
from ...errors import BushelworksError
from ...persistence.mappers import sample_from_rows, ticket_from_row
from ...persistence.unitofwork import UnitOfWork
from ...services.intake import receive
from ...services.scalehouse import record_load
from ..deps import get_rules, get_uow, paging
from ..schemas import (
    GradedLoadOut,
    GradeOut,
    Money,
    Page,
    Quantity,
    SampleReading,
    SplitIn,
    TicketIn,
    TicketOut,
)
from ..security import scale_access

# The driveway terminal holds a scale key and needs to both write and read
# tickets, so the whole router sits at that level rather than splitting the
# reads down to a read key nobody at the scale house has.
router = APIRouter(
    prefix="/tickets",
    tags=["tickets"],
    dependencies=[Depends(scale_access)],
)


def _to_domain(body: TicketIn) -> ScaleTicket:
    return ScaleTicket(
        ticket_number=body.ticket_number,
        scale_date=body.scale_date,
        location_code=body.location_code,
        commodity_code=body.commodity_code,
        gross_pounds=body.gross_pounds,
        tare_pounds=body.tare_pounds,
        disposition=body.disposition,
        splits=[
            SplitShare(party_id=s.party_code, percent=s.percent, role=s.role)
            for s in body.splits
        ],
        sample={reading.factor: reading.value for reading in body.sample},
        dockage_percent=body.dockage_percent,
        truck_id=body.truck_id,
        driver=body.driver,
        farm=body.farm,
        field_name=body.field_name,
        contract_number=body.contract_number,
        remarks=body.remarks,
    )


def _to_out(row) -> TicketOut:
    return TicketOut(
        ticket_number=row.ticket_number,
        location_code=row.location_code,
        scale_date=row.scale_date,
        commodity_code=row.commodity_code,
        party_code=row.party_code,
        gross_pounds=row.gross_pounds,
        tare_pounds=row.tare_pounds,
        net_pounds=row.net_pounds,
        dockage_percent=row.dockage_percent,
        disposition=row.disposition,
        contract_number=row.contract_number,
        voided=row.voided,
        settled=row.settled,
        sample=[
            SampleReading(factor=factor, value=value)
            for factor, value in sorted(
                sample_from_rows(list(row.samples)).items(), key=lambda kv: kv[0].value
            )
        ],
        splits=[
            SplitIn(party_code=split.party_code, percent=split.percent, role=split.role)
            for split in row.splits
        ],
    )


@router.post(
    "",
    response_model=GradedLoadOut,
    status_code=status.HTTP_201_CREATED,
    summary="Record a load across the scale",
)
def create_ticket(
    body: TicketIn,
    response: Response,
    uow: UnitOfWork = Depends(get_uow),
    rules=Depends(get_rules),
) -> GradedLoadOut:
    ticket = _to_domain(body)
    recorded = record_load(uow, ticket, body.party_code, rules)
    uow.commit()
    response.headers["Location"] = f"/tickets/{ticket.location_code}/{ticket.ticket_number}"
    return _load_out(recorded.load)


def _load_out(load) -> GradedLoadOut:
    return GradedLoadOut(
        ticket_number=load.ticket_number,
        commodity_code=load.commodity_code,
        gross_bushels=load.gross_bushels,
        clean_bushels=load.clean_bushels,
        dry_bushels=load.dry_bushels,
        shrink_bushels=load.shrink_bushels,
        shrink_percent=load.shrink_percent,
        moisture_points=load.moisture_points,
        discount_rate=load.discount_rate,
        grade=GradeOut(
            grade=load.grade.grade.value,
            limiting_factors=[f.value for f in load.grade.limiting_factors],
        ),
    )


class BatchIn(Money):
    """A run of tickets the driveway terminal held while the line was down."""

    tickets: list[TicketIn] = Field(min_length=1, max_length=200)
    stop_on_first_refusal: bool = False


class BatchResultOut(Money):
    ticket_number: str
    accepted: bool
    code: str | None = None
    detail: str | None = None
    dry_bushels: Quantity | None = None


class BatchOut(Money):
    accepted: int
    refused: int
    results: list[BatchResultOut]

    @property
    def is_clean(self) -> bool:
        return self.refused == 0


@router.post(
    "/batch",
    response_model=BatchOut,
    status_code=status.HTTP_207_MULTI_STATUS,
    summary="Take a run of tickets the terminal held while the line was down",
)
def create_batch(
    body: BatchIn,
    uow: UnitOfWork = Depends(get_uow),
    rules=Depends(get_rules),
) -> BatchOut:
    """Record several tickets, reporting each one on its own.

    A refused ticket does not cost the rest of the batch. The terminal holds
    tickets when the line is down and sends nine at once when it comes back;
    one duplicate in the middle used to lose the other eight, and the driver of
    the eighth had already gone home.

    Each ticket is committed as it is taken so a later refusal cannot roll an
    earlier one back.
    """
    results: list[BatchResultOut] = []
    accepted = 0
    for body_ticket in body.tickets:
        try:
            recorded = record_load(
                uow, _to_domain(body_ticket), body_ticket.party_code, rules
            )
            uow.commit()
            accepted += 1
            results.append(
                BatchResultOut(
                    ticket_number=body_ticket.ticket_number,
                    accepted=True,
                    dry_bushels=recorded.load.dry_bushels,
                )
            )
        except BushelworksError as refusal:
            uow.rollback()
            results.append(
                BatchResultOut(
                    ticket_number=body_ticket.ticket_number,
                    accepted=False,
                    code=refusal.code,
                    detail=refusal.message,
                )
            )
            if body.stop_on_first_refusal:
                break
    return BatchOut(
        accepted=accepted,
        refused=len(results) - accepted,
        results=results,
    )


@router.get("/{location_code}/{ticket_number}", response_model=TicketOut)
def read_ticket(
    location_code: str,
    ticket_number: str,
    uow: UnitOfWork = Depends(get_uow),
) -> TicketOut:
    return _to_out(uow.tickets.row(location_code, ticket_number))


@router.get("/{location_code}/{ticket_number}/grade", response_model=GradedLoadOut)
def read_grade(
    location_code: str,
    ticket_number: str,
    uow: UnitOfWork = Depends(get_uow),
    rules=Depends(get_rules),
) -> GradedLoadOut:
    """Regrade a stored ticket against the tables in force on its scale date.

    Recomputed rather than stored. A stored grade is a copy that goes stale the
    moment a schedule is corrected, and a ticket is regraded often enough at the
    window that the recomputation is worth more than the cache.
    """
    row = uow.tickets.row(location_code, ticket_number)
    return _load_out(receive(ticket_from_row(row), rules))


@router.post(
    "/{location_code}/{ticket_number}/void",
    response_model=TicketOut,
    summary="Void a ticket, keeping the original",
)
def void_ticket(
    location_code: str,
    ticket_number: str,
    reason: str = Query(min_length=1, max_length=200),
    uow: UnitOfWork = Depends(get_uow),
) -> TicketOut:
    row = uow.tickets.void(location_code, ticket_number, reason)
    uow.commit()
    return _to_out(row)


@router.get("", response_model=Page)
def list_tickets(
    party_code: str | None = None,
    location_code: str | None = None,
    since: date | None = None,
    unsettled_only: bool = False,
    page: tuple[int, int] = Depends(paging),
    uow: UnitOfWork = Depends(get_uow),
) -> Page:
    offset, limit = page
    if unsettled_only:
        rows = uow.tickets.awaiting_settlement(location_code)
    elif party_code:
        rows = uow.tickets.for_party(party_code, since)
    else:
        rows = uow.tickets.awaiting_settlement(location_code)
    if location_code and party_code:
        rows = [row for row in rows if row.location_code == location_code]
    total = len(rows)
    window = rows[offset : offset + limit]
    return Page(
        items=[_to_out(row) for row in window],
        total=total,
        offset=offset,
        limit=limit,
    )
