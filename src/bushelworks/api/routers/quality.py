"""Complaint routes.

Filing a complaint is a POST and closing one is a POST to its own path rather
than a PATCH on the complaint. The distinction matters: closing runs the rules
about credits and dates, and a general purpose PATCH that let a caller set
``outcome`` and ``credit`` directly would walk straight past all of them.

Office key throughout. A complaint names a producer, a credit and sometimes a
buyer who refused a car, and none of that belongs on the scale terminal.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal
from typing import Any

from fastapi import APIRouter, Depends, status
from pydantic import Field, field_validator

from ...domain.complaints import Complaint, Outcome, Source
from ...persistence.unitofwork import UnitOfWork
from ...services.quality import (
    RETENTION_DAYS,
    act,
    close,
    file_complaint,
    indefensible,
    open_complaints,
    repeat_bins,
    summary,
    to_domain,
)
from ..deps import get_uow
from ..schemas import Money, Quantity, _as_decimal
from ..security import office_access

router = APIRouter(
    prefix="/complaints",
    tags=["complaints"],
    dependencies=[Depends(office_access)],
)


class ComplaintIn(Money):
    reference: str = Field(min_length=1, max_length=24)
    source: Source
    subject: str = Field(min_length=1, max_length=200)
    raised_on: date
    delivered_on: date
    party_code: str | None = Field(default=None, max_length=24)
    bin_id: str | None = Field(default=None, max_length=12)
    ticket_number: str | None = Field(default=None, max_length=24)
    sale_number: str | None = Field(default=None, max_length=24)
    probe_id: str | None = Field(default=None, max_length=24)


class ActionIn(Money):
    taken_on: date
    taken_by: str = Field(min_length=1, max_length=40)
    what: str = Field(min_length=1, max_length=200)


class CloseIn(Money):
    outcome: Outcome
    on: date
    credit: Quantity = Decimal("0.00")
    note: str = Field(default="", max_length=400)

    @field_validator("credit", mode="before")
    @classmethod
    def _decimal(cls, value: Any) -> Decimal:
        return _as_decimal(value)


class ComplaintOut(Money):
    reference: str
    source: Source
    subject: str
    raised_on: date
    delivered_on: date
    days_after: int
    party_code: str | None
    bin_id: str | None
    ticket_number: str | None
    sale_number: str | None
    probe_id: str | None
    outcome: Outcome
    credit: Quantity
    closed_on: date | None
    note: str
    is_open: bool
    is_late: bool
    sample_is_available: bool


class SummaryOut(Money):
    opens: date
    closes: date
    count: int
    open: int
    late: int
    upheld_rate: Quantity
    credits: Quantity
    by_source: dict[str, int]
    indefensible: list[str]
    repeat_bins: list[str]
    window_days: int


def _out(complaint: Complaint) -> ComplaintOut:
    return ComplaintOut(
        reference=complaint.reference,
        source=complaint.source,
        subject=complaint.subject,
        raised_on=complaint.raised_on,
        delivered_on=complaint.delivered_on,
        days_after=complaint.days_after,
        party_code=complaint.party_code,
        bin_id=complaint.bin_id,
        ticket_number=complaint.ticket_number,
        sale_number=complaint.sale_number,
        probe_id=complaint.probe_id,
        outcome=complaint.outcome,
        credit=complaint.credit,
        closed_on=complaint.closed_on,
        note=complaint.note,
        is_open=complaint.is_open,
        is_late=complaint.is_late,
        sample_is_available=complaint.sample_is_available(RETENTION_DAYS),
    )


@router.post("", response_model=ComplaintOut, status_code=status.HTTP_201_CREATED)
def take_complaint(
    body: ComplaintIn,
    uow: UnitOfWork = Depends(get_uow),
) -> ComplaintOut:
    """File a complaint. A named ticket is checked against the ticket file."""
    row = file_complaint(
        uow,
        reference=body.reference,
        source=body.source,
        subject=body.subject,
        raised_on=body.raised_on,
        delivered_on=body.delivered_on,
        party_code=body.party_code,
        bin_id=body.bin_id,
        ticket_number=body.ticket_number,
        sale_number=body.sale_number,
        probe_id=body.probe_id,
    )
    out = _out(to_domain(row))
    uow.commit()
    return out


@router.get("/{reference}", response_model=ComplaintOut)
def read_complaint(
    reference: str,
    uow: UnitOfWork = Depends(get_uow),
) -> ComplaintOut:
    return _out(to_domain(uow.complaints.row(reference)))


@router.get("", response_model=list[ComplaintOut])
def list_open(
    bin_id: str | None = None,
    uow: UnitOfWork = Depends(get_uow),
) -> list[ComplaintOut]:
    """Everything still open, oldest first."""
    return [_out(item) for item in open_complaints(uow, bin_id)]


@router.post("/{reference}/actions", status_code=status.HTTP_201_CREATED)
def add_action(
    reference: str,
    body: ActionIn,
    uow: UnitOfWork = Depends(get_uow),
) -> dict[str, str]:
    """Record what was done about it, in the order it was done."""
    act(uow, reference, body.taken_on, body.taken_by, body.what)
    uow.commit()
    return {"reference": reference, "taken_by": body.taken_by, "what": body.what}


@router.post("/{reference}/close", response_model=ComplaintOut)
def close_complaint(
    reference: str,
    body: CloseIn,
    uow: UnitOfWork = Depends(get_uow),
) -> ComplaintOut:
    """Close it. The rules about credits and dates live in the domain object."""
    row = close(uow, reference, body.outcome, body.on, body.credit, body.note)
    out = _out(to_domain(row))
    uow.commit()
    return out


@router.get("/reports/indefensible", response_model=list[ComplaintOut])
def read_indefensible(
    opens: date,
    closes: date,
    uow: UnitOfWork = Depends(get_uow),
) -> list[ComplaintOut]:
    """Open complaints with no retained sample left to argue from."""
    return [_out(item) for item in indefensible(uow, opens, closes)]


@router.get("/reports/repeat-bins")
def read_repeat_bins(
    opens: date,
    closes: date,
    threshold: int = 3,
    uow: UnitOfWork = Depends(get_uow),
) -> list[str]:
    """Bins complained about more often than the house is comfortable with."""
    return repeat_bins(uow, opens, closes, threshold)


@router.get("/reports/summary", response_model=SummaryOut)
def read_summary(
    opens: date,
    closes: date,
    uow: UnitOfWork = Depends(get_uow),
) -> Any:
    """The page the manager looks at once a month."""
    return summary(uow, opens, closes)
