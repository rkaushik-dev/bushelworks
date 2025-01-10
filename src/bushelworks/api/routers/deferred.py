"""Deferred price routes.

Delivering unpriced grain and pricing it later are two different days and two
different documents, so they are two routes. The position endpoint is the one
the hedge desk polls, because unpriced bushels are the ones the house is long
without having paid for.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal
from typing import Any

from fastapi import APIRouter, Depends, status
from pydantic import Field, field_validator

from ...persistence.unitofwork import UnitOfWork
from ...services.deferred import (
    DEFAULT_TERMS,
    deliver,
    house_exposure,
    overdue,
    position,
    price_lot,
    total_unpriced,
    unpriced_lots,
)
from ..deps import get_uow, paging
from ..schemas import Money, Page, Quantity, _as_decimal
from ..security import office_access

router = APIRouter(
    prefix="/deferred",
    tags=["deferred"],
    dependencies=[Depends(office_access)],
)


class DeliverIn(Money):
    lot_id: str = Field(min_length=1, max_length=24)
    party_code: str = Field(min_length=1, max_length=24)
    commodity_code: str = Field(min_length=1, max_length=8)
    location_code: str = Field(min_length=1, max_length=12)
    bushels: Quantity
    delivered_on: date

    @field_validator("bushels", mode="before")
    @classmethod
    def _decimal(cls, value: Any) -> Decimal:
        return _as_decimal(value)


class PriceIn(Money):
    bushels: Quantity
    unit_price: Quantity
    priced_on: date

    @field_validator("bushels", "unit_price", mode="before")
    @classmethod
    def _decimal(cls, value: Any) -> Decimal:
        return _as_decimal(value)


class LotOut(Money):
    lot_id: str
    party_code: str
    commodity_code: str
    location_code: str
    bushels: Quantity
    open_bushels: Quantity
    delivered_on: date


class PricedOut(Money):
    lot_id: str
    bushels: Quantity
    unit_price: Quantity
    gross: Quantity
    service_fee: Quantity
    net: Quantity


class PositionOut(Money):
    party_code: str
    commodity_code: str
    bushels: Quantity
    oldest_delivery: date
    service_owed: Quantity
    overdue_lots: list[str]
    is_overdue: bool


def _lot_out(row) -> LotOut:
    return LotOut(
        lot_id=row.lot_id,
        party_code=row.party_code,
        commodity_code=row.commodity_code,
        location_code=row.location_code,
        bushels=row.bushels,
        open_bushels=row.open_bushels,
        delivered_on=row.stored_on,
    )


@router.post("/lots", response_model=LotOut, status_code=status.HTTP_201_CREATED)
def take_delivery(body: DeliverIn, uow: UnitOfWork = Depends(get_uow)) -> LotOut:
    row = deliver(
        uow,
        lot_id=body.lot_id,
        party_code=body.party_code,
        commodity_code=body.commodity_code,
        location_code=body.location_code,
        bushels=body.bushels,
        delivered_on=body.delivered_on,
    )
    out = _lot_out(row)
    uow.commit()
    return out


@router.get("/lots", response_model=Page)
def list_lots(
    party_code: str | None = None,
    commodity_code: str | None = None,
    location_code: str | None = None,
    page: tuple[int, int] = Depends(paging),
    uow: UnitOfWork = Depends(get_uow),
) -> Page:
    offset, limit = page
    rows = unpriced_lots(uow, party_code, commodity_code, location_code)
    window = rows[offset : offset + limit]
    return Page(
        items=[_lot_out(row) for row in window],
        total=len(rows),
        offset=offset,
        limit=limit,
    )


@router.post("/lots/{lot_id}/pricing", response_model=PricedOut)
def price(
    lot_id: str, body: PriceIn, uow: UnitOfWork = Depends(get_uow)
) -> PricedOut:
    """Fix a price on some or all of a lot, and say what the fee comes to."""
    gross, fee = price_lot(
        uow, lot_id, body.bushels, body.unit_price, body.priced_on
    )
    out = PricedOut(
        lot_id=lot_id,
        bushels=body.bushels,
        unit_price=body.unit_price,
        gross=gross,
        service_fee=fee,
        net=gross - fee,
    )
    uow.commit()
    return out


@router.get("/position/{party_code}/{commodity_code}", response_model=PositionOut)
def read_position(
    party_code: str,
    commodity_code: str,
    as_of: date,
    uow: UnitOfWork = Depends(get_uow),
) -> PositionOut:
    from ...errors import LookupFailure

    found = position(uow, party_code, commodity_code, as_of, DEFAULT_TERMS)
    if found is None:
        raise LookupFailure(
            f"{party_code} has no unpriced {commodity_code} on {as_of}"
        )
    return PositionOut(
        party_code=found.party_code,
        commodity_code=found.commodity_code,
        bushels=found.bushels,
        oldest_delivery=found.oldest_delivery,
        service_owed=found.service_owed,
        overdue_lots=list(found.overdue_lots),
        is_overdue=found.is_overdue,
    )


@router.get("/exposure/{commodity_code}")
def read_exposure(
    commodity_code: str, as_of: date, uow: UnitOfWork = Depends(get_uow)
) -> dict[str, str]:
    """Bushels the house owns because nobody has priced them yet."""
    return {
        "commodity_code": commodity_code,
        "as_of": as_of.isoformat(),
        "bushels": str(house_exposure(uow, commodity_code, as_of)),
        "total_unpriced": str(total_unpriced(uow)),
    }


@router.get("/overdue", response_model=list[LotOut])
def list_overdue(as_of: date, uow: UnitOfWork = Depends(get_uow)) -> list[LotOut]:
    """Lots past the term the state allows, which the office has to chase."""
    return [_lot_out(row) for row in overdue(uow, as_of, DEFAULT_TERMS)]
