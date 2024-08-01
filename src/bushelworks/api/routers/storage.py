"""Storage routes.

The accrual run is a POST because it changes what every lot has been billed to.
It takes a ``dry_run`` flag rather than having a separate preview route: the
office wants to see the same numbers it is about to commit, and two code paths
would eventually disagree.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal
from typing import Any

from fastapi import APIRouter, Depends, status
from pydantic import Field, field_validator

from ...persistence.unitofwork import UnitOfWork
from ...services.warehousing import (
    accrue,
    cancel_receipt,
    issue_receipt,
    lot,
    on_hand,
    open_lots,
    store,
    withdraw,
)
from ..deps import get_rules, get_uow, paging
from ..schemas import Money, Page, Quantity, _as_decimal
from ..security import office_access

# The accrual run bills producers and a receipt is a negotiable instrument.
router = APIRouter(
    prefix="/storage",
    tags=["storage"],
    dependencies=[Depends(office_access)],
)


class StoreIn(Money):
    lot_id: str = Field(min_length=1, max_length=24)
    party_code: str = Field(min_length=1, max_length=24)
    commodity_code: str = Field(min_length=1, max_length=8)
    location_code: str = Field(min_length=1, max_length=12)
    bushels: Quantity
    stored_on: date
    deferred_price: bool = False

    @field_validator("bushels", mode="before")
    @classmethod
    def _decimal(cls, value: Any) -> Decimal:
        return _as_decimal(value)


class WithdrawIn(Money):
    party_code: str = Field(min_length=1, max_length=24)
    commodity_code: str = Field(min_length=1, max_length=8)
    bushels: Quantity
    withdrawn_on: date
    reason: str = "settled"
    settlement_number: str | None = None
    location_code: str | None = None

    @field_validator("bushels", mode="before")
    @classmethod
    def _decimal(cls, value: Any) -> Decimal:
        return _as_decimal(value)


class ReceiptIn(Money):
    receipt_number: str = Field(min_length=1, max_length=24)
    lot_id: str = Field(min_length=1, max_length=24)
    grade: str = Field(min_length=1, max_length=8)
    issued_on: date
    lienholder: str | None = None


class LotOut(Money):
    lot_id: str
    party_code: str
    commodity_code: str
    location_code: str
    bushels: Quantity
    open_bushels: Quantity
    stored_on: date
    closed_on: date | None = None
    billed_through: date | None = None
    accrued_to_date: Quantity
    is_deferred_price: bool


class AccrualLineOut(Money):
    lot_id: str
    party_code: str
    commodity_code: str
    open_bushels: Quantity
    from_day: date | None = None
    through: date
    dollars: Quantity


class AccrualOut(Money):
    through: date
    total: Quantity
    recorded: bool
    lines: list[AccrualLineOut]


class ReceiptOut(Money):
    receipt_number: str
    party_code: str
    commodity_code: str
    location_code: str
    bushels: Quantity
    grade: str
    issued_on: date
    cancelled_on: date | None = None
    lienholder: str | None = None
    is_live: bool


def _lot_out(row) -> LotOut:
    return LotOut(
        lot_id=row.lot_id,
        party_code=row.party_code,
        commodity_code=row.commodity_code,
        location_code=row.location_code,
        bushels=row.bushels,
        open_bushels=row.open_bushels,
        stored_on=row.stored_on,
        closed_on=row.closed_on,
        billed_through=row.billed_through,
        accrued_to_date=row.accrued_to_date,
        is_deferred_price=row.is_deferred_price,
    )


def _receipt_out(row) -> ReceiptOut:
    return ReceiptOut(
        receipt_number=row.receipt_number,
        party_code=row.party_code,
        commodity_code=row.commodity_code,
        location_code=row.location_code,
        bushels=row.bushels,
        grade=row.grade,
        issued_on=row.issued_on,
        cancelled_on=row.cancelled_on,
        lienholder=row.lienholder,
        is_live=row.is_live,
    )


@router.post("/lots", response_model=LotOut, status_code=status.HTTP_201_CREATED)
def open_lot(
    body: StoreIn,
    uow: UnitOfWork = Depends(get_uow),
) -> LotOut:
    row = store(
        uow,
        lot_id=body.lot_id,
        party_code=body.party_code,
        commodity_code=body.commodity_code,
        location_code=body.location_code,
        bushels=body.bushels,
        stored_on=body.stored_on,
        deferred_price=body.deferred_price,
    )
    out = _lot_out(row)
    uow.commit()
    return out


@router.get("/lots/{lot_id}", response_model=LotOut)
def read_lot(
    lot_id: str,
    uow: UnitOfWork = Depends(get_uow),
) -> LotOut:
    return _lot_out(lot(uow, lot_id))


@router.get("/lots", response_model=Page)
def list_lots(
    party_code: str | None = None,
    commodity_code: str | None = None,
    location_code: str | None = None,
    page: tuple[int, int] = Depends(paging),
    uow: UnitOfWork = Depends(get_uow),
) -> Page:
    offset, limit = page
    rows = open_lots(uow, party_code, commodity_code, location_code)
    window = rows[offset : offset + limit]
    return Page(
        items=[_lot_out(row) for row in window],
        total=len(rows),
        offset=offset,
        limit=limit,
    )


@router.post("/withdrawals", response_model=list[LotOut])
def make_withdrawal(
    body: WithdrawIn,
    uow: UnitOfWork = Depends(get_uow),
) -> list[LotOut]:
    written = withdraw(
        uow,
        party_code=body.party_code,
        commodity_code=body.commodity_code,
        bushels=body.bushels,
        withdrawn_on=body.withdrawn_on,
        reason=body.reason,
        settlement_number=body.settlement_number,
        location_code=body.location_code,
    )
    touched = [_lot_out(row.lot) for row in written]
    uow.commit()
    return touched


@router.post("/accruals", response_model=AccrualOut)
def run_accrual(
    through: date,
    location_code: str | None = None,
    dry_run: bool = False,
    uow: UnitOfWork = Depends(get_uow),
    rules=Depends(get_rules),
) -> AccrualOut:
    run = accrue(uow, rules, through, location_code, record=not dry_run)
    out = AccrualOut(
        through=run.through,
        total=run.total,
        recorded=not dry_run,
        lines=[
            AccrualLineOut(
                lot_id=line.lot_id,
                party_code=line.party_code,
                commodity_code=line.commodity_code,
                open_bushels=line.open_bushels,
                from_day=line.from_day,
                through=line.through,
                dollars=line.dollars,
            )
            for line in run.lines
        ],
    )
    if dry_run:
        uow.rollback()
    else:
        uow.commit()
    return out


@router.post("/receipts", response_model=ReceiptOut, status_code=status.HTTP_201_CREATED)
def write_receipt(
    body: ReceiptIn,
    uow: UnitOfWork = Depends(get_uow),
) -> ReceiptOut:
    row = issue_receipt(
        uow,
        receipt_number=body.receipt_number,
        lot_id=body.lot_id,
        grade=body.grade,
        issued_on=body.issued_on,
        lienholder=body.lienholder,
    )
    out = _receipt_out(row)
    uow.commit()
    return out


@router.post("/receipts/{receipt_number}/cancel", response_model=ReceiptOut)
def cancel(
    receipt_number: str,
    on: date,
    uow: UnitOfWork = Depends(get_uow),
) -> ReceiptOut:
    row = cancel_receipt(uow, receipt_number, on)
    out = _receipt_out(row)
    uow.commit()
    return out


@router.get("/on-hand/{commodity_code}")
def read_on_hand(
    commodity_code: str,
    location_code: str | None = None,
    uow: UnitOfWork = Depends(get_uow),
) -> dict[str, str]:
    """Bushels the house is holding for producers, which it does not own."""
    return {
        "commodity_code": commodity_code,
        "location_code": location_code or "*",
        "bushels": str(on_hand(uow, commodity_code, location_code)),
    }
