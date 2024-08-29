"""Sale and shipment routes.

Loading out is a POST to the sale's shipments. Posting the buyer's settlement is
a POST to one shipment, and rejecting a car is a third, because all three are
things that happen on different days and are done by different people.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal
from typing import Any

from fastapi import APIRouter, Depends, Query, status
from pydantic import Field, field_validator

from ...persistence.base import as_decimal
from ...persistence.models import SaleRow
from ...persistence.unitofwork import UnitOfWork
from ...services.shipping import (
    load_out,
    open_sales,
    reject_shipment,
    sale,
    settle_shipment,
    sold_forward,
)
from ..deps import get_uow, paging
from ..schemas import Money, Page, Quantity, _as_decimal
from ..security import office_access

router = APIRouter(
    prefix="/sales",
    tags=["sales"],
    dependencies=[Depends(office_access)],
)


class SaleIn(Money):
    number: str = Field(min_length=1, max_length=24)
    kind: str = Field(default="forward", max_length=12)
    buyer: str = Field(min_length=1, max_length=80)
    destination: str = Field(default="terminal", max_length=16)
    carrier: str = Field(default="truck", max_length=8)
    commodity_code: str = Field(min_length=1, max_length=8)
    origin_code: str = Field(min_length=1, max_length=12)
    written_on: date
    bushels: Quantity
    window_opens: date
    window_closes: date
    flat_price: Quantity | None = None
    futures_price: Quantity | None = None
    basis: Quantity | None = None
    freight_per_bushel: Quantity = Decimal("0")
    tolerance_percent: Quantity = Decimal("2")
    spec_max_moisture: Quantity = Decimal("15.0")
    spec_min_test_weight: Quantity = Decimal("54.0")
    spec_max_damage: Quantity = Decimal("5.0")
    spec_max_foreign: Quantity = Decimal("3.0")

    @field_validator(
        "bushels",
        "flat_price",
        "futures_price",
        "basis",
        "freight_per_bushel",
        "tolerance_percent",
        "spec_max_moisture",
        "spec_min_test_weight",
        "spec_max_damage",
        "spec_max_foreign",
        mode="before",
    )
    @classmethod
    def _decimal(cls, value: Any) -> Any:
        return None if value is None else _as_decimal(value)


class LoadIn(Money):
    reference: str = Field(min_length=1, max_length=24)
    pounds: Quantity
    shipped_on: date
    carrier_id: str | None = None

    @field_validator("pounds", mode="before")
    @classmethod
    def _decimal(cls, value: Any) -> Decimal:
        return _as_decimal(value)


class SettleShipmentIn(Money):
    destination_pounds: Quantity
    destination_grade: str = Field(min_length=1, max_length=8)
    proceeds: Quantity
    settled_on: date

    @field_validator("destination_pounds", "proceeds", mode="before")
    @classmethod
    def _decimal(cls, value: Any) -> Decimal:
        return _as_decimal(value)


class ShipmentOut(Money):
    reference: str
    shipped_on: date
    carrier_id: str | None = None
    origin_bushels: Quantity
    destination_bushels: Quantity | None = None
    destination_grade: str | None = None
    settled_on: date | None = None
    proceeds: Quantity | None = None
    rejected: bool
    rejection_reason: str | None = None
    transit_shrink: Quantity | None = None


class SaleOut(Money):
    number: str
    kind: str
    buyer: str
    destination: str
    carrier: str
    commodity_code: str
    origin_code: str
    written_on: date
    bushels: Quantity
    shipped: Quantity
    remaining: Quantity
    window_opens: date
    window_closes: date
    freight_per_bushel: Quantity
    status: str
    shipments: list[ShipmentOut] = Field(default_factory=list)


def _shipment_out(row) -> ShipmentOut:
    return ShipmentOut(
        reference=row.reference,
        shipped_on=row.shipped_on,
        carrier_id=row.carrier_id,
        origin_bushels=row.origin_bushels,
        destination_bushels=row.destination_bushels,
        destination_grade=row.destination_grade,
        settled_on=row.settled_on,
        proceeds=row.proceeds,
        rejected=row.rejected,
        rejection_reason=row.rejection_reason,
        transit_shrink=row.transit_shrink,
    )


def _sale_out(row: SaleRow) -> SaleOut:
    left = as_decimal(row.bushels) - as_decimal(row.shipped)
    return SaleOut(
        number=row.number,
        kind=row.kind,
        buyer=row.buyer,
        destination=row.destination,
        carrier=row.carrier,
        commodity_code=row.commodity_code,
        origin_code=row.origin_code,
        written_on=row.written_on,
        bushels=row.bushels,
        shipped=row.shipped,
        remaining=left if left > 0 else Decimal("0.0000"),
        window_opens=row.window_opens,
        window_closes=row.window_closes,
        freight_per_bushel=row.freight_per_bushel,
        status=row.status,
        shipments=[_shipment_out(item) for item in row.shipments],
    )


@router.post("", response_model=SaleOut, status_code=status.HTTP_201_CREATED)
def write_sale(body: SaleIn, uow: UnitOfWork = Depends(get_uow)) -> SaleOut:
    from sqlalchemy import select

    from ...domain.contracts import DeliveryWindow
    from ...domain.sales import Carrier, Destination, SaleContract, SaleKind, Specification
    from ...errors import ConflictError
    from ...persistence.mappers import sale_to_row

    existing = uow.session.scalar(select(SaleRow).where(SaleRow.number == body.number))
    if existing is not None:
        raise ConflictError(f"sale {body.number} already exists")

    # Built as a domain object first so the sale's own rules refuse a bad body
    # before it reaches a check constraint. A constraint violation escapes as a
    # database error, which is a 500 and tells the caller nothing.
    contract = SaleContract(
        number=body.number,
        kind=SaleKind(body.kind),
        buyer=body.buyer,
        destination=Destination(body.destination),
        carrier=Carrier(body.carrier),
        commodity_code=body.commodity_code,
        origin_code=body.origin_code,
        written_on=body.written_on,
        bushels=body.bushels,
        window=DeliveryWindow(body.window_opens, body.window_closes),
        specification=Specification(
            max_moisture=body.spec_max_moisture,
            min_test_weight=body.spec_min_test_weight,
            max_damage=body.spec_max_damage,
            max_foreign_material=body.spec_max_foreign,
        ),
        futures_price=body.futures_price,
        basis=body.basis,
        flat_price=body.flat_price,
        tolerance_percent=body.tolerance_percent,
        freight_per_bushel=body.freight_per_bushel,
    )
    row = sale_to_row(contract)
    uow.session.add(row)
    uow.flush()
    out = _sale_out(row)
    uow.commit()
    return out


@router.get("/{number}", response_model=SaleOut)
def read_sale(number: str, uow: UnitOfWork = Depends(get_uow)) -> SaleOut:
    return _sale_out(sale(uow, number))


@router.get("", response_model=Page)
def list_sales(
    commodity_code: str | None = None,
    on: date | None = None,
    page: tuple[int, int] = Depends(paging),
    uow: UnitOfWork = Depends(get_uow),
) -> Page:
    offset, limit = page
    rows = open_sales(uow, commodity_code, on)
    window = rows[offset : offset + limit]
    return Page(
        items=[_sale_out(row) for row in window],
        total=len(rows),
        offset=offset,
        limit=limit,
    )


@router.post(
    "/{number}/shipments",
    response_model=SaleOut,
    status_code=status.HTTP_201_CREATED,
)
def ship(
    number: str, body: LoadIn, uow: UnitOfWork = Depends(get_uow)
) -> SaleOut:
    load_out(
        uow,
        sale_number=number,
        reference=body.reference,
        pounds=body.pounds,
        shipped_on=body.shipped_on,
        carrier_id=body.carrier_id,
    )
    out = _sale_out(sale(uow, number))
    uow.commit()
    return out


@router.post("/{number}/shipments/{reference}/settlement", response_model=ShipmentOut)
def settle(
    number: str,
    reference: str,
    body: SettleShipmentIn,
    uow: UnitOfWork = Depends(get_uow),
) -> ShipmentOut:
    row = settle_shipment(
        uow,
        sale_number=number,
        reference=reference,
        destination_pounds=body.destination_pounds,
        destination_grade=body.destination_grade,
        proceeds=body.proceeds,
        settled_on=body.settled_on,
    )
    out = _shipment_out(row)
    uow.commit()
    return out


@router.post("/{number}/shipments/{reference}/rejection", response_model=ShipmentOut)
def reject(
    number: str,
    reference: str,
    reason: str = Query(min_length=1, max_length=200),
    uow: UnitOfWork = Depends(get_uow),
) -> ShipmentOut:
    row = reject_shipment(uow, number, reference, reason)
    out = _shipment_out(row)
    uow.commit()
    return out


@router.get("/position/{commodity_code}")
def read_sold_forward(
    commodity_code: str,
    on: date,
    origin_code: str | None = None,
    uow: UnitOfWork = Depends(get_uow),
) -> dict[str, str]:
    """Bushels sold and not yet loaded, which is what makes the house short."""
    return {
        "commodity_code": commodity_code,
        "on": on.isoformat(),
        "sold_forward": str(sold_forward(uow, commodity_code, on, origin_code)),
    }
