"""Audit and day close routes.

Read only except for the close, which is a POST because running it moves what
each lot has been billed to. The audit trail has no write route at all: entries
are written by the services that do the thing being recorded, and a route that
could write one directly would let somebody put a lie in the evidence.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal

from fastapi import APIRouter, Depends, Query, Response

from ...persistence.unitofwork import UnitOfWork
from ...services.audit import Entry, for_day, for_document, for_party
from ...services.dayend import Close, render, run_close
from ..deps import get_settings, get_uow
from ..schemas import Money, Quantity
from ..security import office_access

router = APIRouter(
    prefix="/ops",
    tags=["ops"],
    dependencies=[Depends(office_access)],
)


class EntryOut(Money):
    recorded_at: str
    actor: str
    action: str
    document_kind: str
    document_number: str
    party_code: str | None = None
    amount: Quantity | None = None
    detail: str | None = None


class DiscrepancyOut(Money):
    kind: str
    detail: str


class CloseOut(Money):
    day: date
    location_code: str
    tickets_written: int
    tickets_voided: int
    bushels_in: Quantity
    bushels_out: Quantity
    net_bushels: Quantity
    settlements_written: int
    settled_value: Quantity
    is_clean: bool
    discrepancies: list[DiscrepancyOut]


def _entry_out(entry: Entry) -> EntryOut:
    return EntryOut(
        recorded_at=entry.recorded_at,
        actor=entry.actor,
        action=entry.action,
        document_kind=entry.document_kind,
        document_number=entry.document_number,
        party_code=entry.party_code,
        amount=entry.amount,
        detail=entry.detail,
    )


def _close_out(close: Close) -> CloseOut:
    return CloseOut(
        day=close.day,
        location_code=close.location_code,
        tickets_written=close.tickets_written,
        tickets_voided=close.tickets_voided,
        bushels_in=close.bushels_in,
        bushels_out=close.bushels_out,
        net_bushels=close.net_bushels,
        settlements_written=close.settlements_written,
        settled_value=close.settled_value,
        is_clean=close.is_clean,
        discrepancies=[
            DiscrepancyOut(kind=item.kind, detail=item.detail)
            for item in close.discrepancies
        ],
    )


# The document route is under its own segment rather than at the top of
# ``/audit``. A path of ``/audit/{kind}/{number}`` swallows ``/audit/party/X``
# and answers it as a document of kind "party", which it silently did.
@router.get(
    "/audit/documents/{document_kind}/{document_number}",
    response_model=list[EntryOut],
)
def read_document_trail(
    document_kind: str,
    document_number: str,
    uow: UnitOfWork = Depends(get_uow),
) -> list[EntryOut]:
    """Everything that ever happened to one document, oldest first."""
    return [_entry_out(entry) for entry in for_document(uow, document_kind, document_number)]


@router.get("/audit/party/{party_code}", response_model=list[EntryOut])
def read_party_trail(
    party_code: str,
    limit: int = Query(default=200, ge=1, le=1000),
    uow: UnitOfWork = Depends(get_uow),
) -> list[EntryOut]:
    """A producer's trail, newest first."""
    return [_entry_out(entry) for entry in for_party(uow, party_code, limit)]


@router.get("/audit/day/{day}", response_model=list[EntryOut])
def read_day_trail(
    day: date,
    action: str | None = None,
    uow: UnitOfWork = Depends(get_uow),
) -> list[EntryOut]:
    return [_entry_out(entry) for entry in for_day(uow, day, action)]


@router.post("/close/{day}", response_model=CloseOut)
def close_day(
    day: date,
    location_code: str = "*",
    position_tolerance: Decimal = Query(default=Decimal("250000")),
    uow: UnitOfWork = Depends(get_uow),
) -> CloseOut:
    """Close a day. The accrual side of it commits; the checks do not write."""
    close = run_close(uow, day, location_code, position_tolerance)
    out = _close_out(close)
    uow.commit()
    return out


@router.get(
    "/close/{day}/sheet",
    response_class=Response,
    responses={200: {"content": {"text/plain": {}}}},
)
def print_close(
    day: date,
    location_code: str = "*",
    position_tolerance: Decimal = Query(default=Decimal("250000")),
    uow: UnitOfWork = Depends(get_uow),
    settings=Depends(get_settings),
) -> Response:
    """The close as it goes in the day book."""
    close = run_close(uow, day, location_code, position_tolerance)
    return Response(
        content=render(close, house_name=settings.house_name),
        media_type="text/plain; charset=utf-8",
    )
