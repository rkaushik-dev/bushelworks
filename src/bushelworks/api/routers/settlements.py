"""Settlement routes.

Settling is a POST that creates a document, so it is a POST to the collection
and it returns 201 with the finished statement. Paying is a POST to a sub
resource, because the cheque number and the date it cleared are facts about the
payment and not about the settlement.

The printable statement is the same resource with a different representation.
Ask for ``text/plain`` and the eighty column form comes back.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal
from typing import Any

from fastapi import APIRouter, Depends, Response, status
from pydantic import Field, field_validator

from ...persistence.mappers import settlement_from_row
from ...persistence.unitofwork import UnitOfWork
from ...reports.statement import register, render
from ...services.settlement_run import settle_producer
from ...services.settling import Advance, DiscountBasis, StorageCharge
from ..deps import get_rules, get_settings, get_uow, paging
from ..schemas import Money, Page, Quantity, SettlementLineOut, SettlementOut, _as_decimal
from ..security import office_access

# Settling writes cheques. Nothing below the office role gets in here at all,
# including the reads, because a settlement shows what a producer was paid.
router = APIRouter(
    prefix="/settlements",
    tags=["settlements"],
    dependencies=[Depends(office_access)],
)


class AdvanceIn(Money):
    reference: str = Field(min_length=1, max_length=24)
    dollars: Quantity
    advanced_on: date

    @field_validator("dollars", mode="before")
    @classmethod
    def _decimal(cls, value: Any) -> Decimal:
        return _as_decimal(value)


class StorageIn(Money):
    lot_id: str = Field(min_length=1, max_length=24)
    dollars: Quantity
    through: date

    @field_validator("dollars", mode="before")
    @classmethod
    def _decimal(cls, value: Any) -> Decimal:
        return _as_decimal(value)


class SettleIn(Money):
    party_code: str = Field(min_length=1, max_length=24)
    location_code: str = Field(min_length=1, max_length=12)
    settled_on: date
    spot_price: Quantity
    discount_basis: DiscountBasis = DiscountBasis.DRY
    ticket_numbers: list[str] | None = None
    advances: list[AdvanceIn] = Field(default_factory=list)
    storage: list[StorageIn] = Field(default_factory=list)

    @field_validator("spot_price", mode="before")
    @classmethod
    def _decimal(cls, value: Any) -> Decimal:
        return _as_decimal(value)


class PayIn(Money):
    cheque_number: str = Field(min_length=1, max_length=16)
    paid_on: date


def _to_out(settlement) -> SettlementOut:
    return SettlementOut(
        number=settlement.number,
        settled_on=settlement.settled_on,
        party_code=settlement.party_id,
        location_code=settlement.location_code,
        gross=settlement.gross,
        deductions=settlement.deductions,
        net=settlement.net,
        ticket_numbers=list(settlement.ticket_numbers),
        contract_numbers=list(settlement.contract_numbers),
        lines=[
            SettlementLineOut(
                kind=line.kind.value,
                code=line.code,
                description=line.description,
                dollars=line.dollars,
                quantity=line.quantity,
                rate=line.rate,
            )
            for line in settlement.lines
        ],
    )


@router.post("", response_model=SettlementOut, status_code=status.HTTP_201_CREATED)
def settle(
    body: SettleIn,
    response: Response,
    uow: UnitOfWork = Depends(get_uow),
    rules=Depends(get_rules),
) -> SettlementOut:
    statement = settle_producer(
        uow,
        party_code=body.party_code,
        location_code=body.location_code,
        rules=rules,
        settled_on=body.settled_on,
        spot_price=body.spot_price,
        discount_basis=body.discount_basis,
        advances=[
            Advance(a.reference, a.dollars, a.advanced_on) for a in body.advances
        ],
        storage=[StorageCharge(s.lot_id, s.dollars, s.through) for s in body.storage],
        ticket_numbers=body.ticket_numbers,
    )
    uow.commit()
    response.headers["Location"] = f"/settlements/{statement.number}"
    return _to_out(statement)


@router.get("/{number}", response_model=SettlementOut)
def read_settlement(
    number: str,
    uow: UnitOfWork = Depends(get_uow),
) -> SettlementOut:
    return _to_out(uow.settlements.get(number))


@router.get(
    "/{number}/statement",
    response_class=Response,
    responses={200: {"content": {"text/plain": {}}}},
    summary="The printable eighty column statement",
)
def print_statement(
    number: str,
    uow: UnitOfWork = Depends(get_uow),
    settings=Depends(get_settings),
) -> Response:
    statement = uow.settlements.get(number)
    return Response(
        content=render(statement, house_name=settings.house_name),
        media_type="text/plain; charset=utf-8",
    )


@router.post("/{number}/payment", response_model=SettlementOut)
def pay_settlement(
    number: str,
    body: PayIn,
    uow: UnitOfWork = Depends(get_uow),
) -> SettlementOut:
    uow.settlements.mark_paid(number, body.cheque_number, body.paid_on)
    uow.commit()
    return _to_out(uow.settlements.get(number))


@router.get("", response_model=Page)
def list_settlements(
    on: date | None = None,
    location_code: str | None = None,
    unpaid_only: bool = False,
    page: tuple[int, int] = Depends(paging),
    uow: UnitOfWork = Depends(get_uow),
) -> Page:
    offset, limit = page
    if unpaid_only:
        rows = uow.settlements.unpaid()
    elif on is not None:
        rows = uow.settlements.for_day(on, location_code)
    else:
        rows = uow.settlements.unpaid()
    window = rows[offset : offset + limit]
    return Page(
        items=[_to_out(settlement_from_row(row)) for row in window],
        total=len(rows),
        offset=offset,
        limit=limit,
    )


@router.get(
    "/register/{on}",
    response_class=Response,
    responses={200: {"content": {"text/plain": {}}}},
    summary="The daily settlement register",
)
def print_register(
    on: date,
    location_code: str | None = None,
    uow: UnitOfWork = Depends(get_uow),
) -> Response:
    rows = uow.settlements.for_day(on, location_code)
    text = register(
        [settlement_from_row(row) for row in rows], on.strftime("%d %B %Y")
    )
    return Response(content=text, media_type="text/plain; charset=utf-8")
