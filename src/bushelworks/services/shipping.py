"""Loading out against a sale.

Shipping is the only place bushels leave the house, so it is the only place the
position goes down. Three things happen and they happen in this order:

1. the load is weighed out and converted to bushels at origin
2. the sale's shipped total moves, which is what stops the next car
3. weeks later the buyer's settlement arrives with a destination weight, a grade
   and a cheque, and the difference between the two weights is transit shrink

Step three is separate on purpose. A shipment with no destination weight is the
normal state for a fortnight, and treating that as incomplete would make the
position wrong for the whole time.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from decimal import Decimal

from sqlalchemy import func, select

from ..domain import commodity as cm
from ..errors import ConflictError, LookupFailure, ValidationError
from ..logging import event, get_logger
from ..money import amount, extend
from ..persistence.base import as_decimal
from ..persistence.models import SaleRow, ShipmentRow
from ..persistence.unitofwork import UnitOfWork
from ..units import BUSHEL_PLACES, pounds_to_bushels, quantize
from . import audit

_log = get_logger("shipping")


class ShippingError(ValidationError):
    """Raised when a load cannot go out as asked."""

    code = "shipping_error"


class SaleNotFound(LookupFailure):
    code = "sale_not_found"


@dataclass(frozen=True, slots=True)
class Loaded:
    """One car or truck off the yard."""

    sale_number: str
    reference: str
    bushels: Decimal
    shipped_on: date
    remaining: Decimal


def sale(uow: UnitOfWork, number: str) -> SaleRow:
    row = uow.session.scalar(select(SaleRow).where(SaleRow.number == number))
    if row is None:
        raise SaleNotFound(f"no sale {number}")
    return row


def open_sales(
    uow: UnitOfWork, commodity_code: str | None = None, on: date | None = None
) -> list[SaleRow]:
    query = select(SaleRow).where(SaleRow.status == "open")
    if commodity_code:
        query = query.where(SaleRow.commodity_code == commodity_code)
    if on is not None:
        query = query.where(
            SaleRow.window_opens <= on, SaleRow.window_closes >= on
        )
    return list(uow.session.scalars(query.order_by(SaleRow.window_closes, SaleRow.number)))


def maximum_for(row: SaleRow) -> Decimal:
    ceiling = as_decimal(row.bushels) * (
        Decimal("100") + as_decimal(row.tolerance_percent)
    ) / Decimal("100")
    return quantize(ceiling, BUSHEL_PLACES)


def load_out(
    uow: UnitOfWork,
    sale_number: str,
    reference: str,
    pounds: Decimal,
    shipped_on: date,
    carrier_id: str | None = None,
    actor: str = audit.SYSTEM,
) -> Loaded:
    """Weigh a load out and put it against a sale."""
    row = sale(uow, sale_number)
    if row.status != "open":
        raise ConflictError(f"{sale_number} is {row.status}, not open")
    if not (row.window_opens <= shipped_on <= row.window_closes):
        raise ShippingError(
            f"{sale_number}: {shipped_on} is outside the shipping window"
        )
    if pounds <= 0:
        raise ShippingError(f"{sale_number}: a load has to weigh something")

    commodity = cm.get(row.commodity_code)
    bushels = pounds_to_bushels(pounds, commodity.standard_test_weight)
    already = as_decimal(row.shipped)
    if already + bushels > maximum_for(row):
        raise ShippingError(
            f"{sale_number}: {bushels} bushels would take it past "
            f"{maximum_for(row)}"
        )

    existing = uow.session.scalar(
        select(ShipmentRow).where(
            ShipmentRow.sale_id == row.id, ShipmentRow.reference == reference
        )
    )
    if existing is not None:
        raise ConflictError(f"{sale_number} already has a shipment {reference}")

    row.shipments.append(
        ShipmentRow(
            reference=reference,
            shipped_on=shipped_on,
            carrier_id=carrier_id,
            origin_pounds=pounds,
            origin_bushels=bushels,
        )
    )
    row.shipped = quantize(already + bushels, BUSHEL_PLACES)
    if row.shipped >= as_decimal(row.bushels):
        row.status = "filled"
    uow.flush()

    audit.record(
        uow,
        audit.Action.APPLIED,
        "sale",
        sale_number,
        actor=actor,
        location_code=row.origin_code,
        effective_on=shipped_on,
        detail=f"{reference}: {bushels} bu out",
    )
    remaining = as_decimal(row.bushels) - as_decimal(row.shipped)
    loaded = Loaded(
        sale_number=sale_number,
        reference=reference,
        bushels=bushels,
        shipped_on=shipped_on,
        remaining=remaining if remaining > 0 else Decimal("0.0000"),
    )
    event(
        _log,
        "shipment.loaded",
        sale=sale_number,
        reference=reference,
        bushels=bushels,
        remaining=loaded.remaining,
    )
    return loaded


def settle_shipment(
    uow: UnitOfWork,
    sale_number: str,
    reference: str,
    destination_pounds: Decimal,
    destination_grade: str,
    proceeds: Decimal,
    settled_on: date,
    actor: str = audit.SYSTEM,
) -> ShipmentRow:
    """Post the buyer's settlement against a shipment."""
    row = sale(uow, sale_number)
    shipment = next(
        (item for item in row.shipments if item.reference == reference), None
    )
    if shipment is None:
        raise SaleNotFound(f"{sale_number} has no shipment {reference}")
    if shipment.is_settled:
        raise ConflictError(
            f"{reference} was settled on {shipment.settled_on}"
        )
    if destination_pounds <= 0:
        raise ShippingError(f"{reference}: destination weight has to be positive")

    commodity = cm.get(row.commodity_code)
    shipment.destination_pounds = destination_pounds
    shipment.destination_bushels = pounds_to_bushels(
        destination_pounds, commodity.standard_test_weight
    )
    shipment.destination_grade = destination_grade
    shipment.proceeds = amount(proceeds)
    shipment.settled_on = settled_on
    uow.flush()

    audit.record(
        uow,
        audit.Action.SETTLED,
        "sale",
        sale_number,
        actor=actor,
        amount=amount(proceeds),
        effective_on=settled_on,
        detail=f"{reference} settled at {destination_grade}",
    )
    return shipment


def reject_shipment(
    uow: UnitOfWork,
    sale_number: str,
    reference: str,
    reason: str,
    actor: str = audit.SYSTEM,
) -> ShipmentRow:
    """Mark a car the buyer refused. The bushels come back onto the position."""
    row = sale(uow, sale_number)
    shipment = next(
        (item for item in row.shipments if item.reference == reference), None
    )
    if shipment is None:
        raise SaleNotFound(f"{sale_number} has no shipment {reference}")
    if shipment.rejected:
        raise ConflictError(f"{reference} is already marked rejected")
    if not reason:
        raise ShippingError(f"{reference}: a rejection needs a reason")

    shipment.rejected = True
    shipment.rejection_reason = reason
    row.shipped = quantize(
        as_decimal(row.shipped) - as_decimal(shipment.origin_bushels), BUSHEL_PLACES
    )
    if row.status == "filled" and as_decimal(row.shipped) < as_decimal(row.bushels):
        row.status = "open"
    uow.flush()
    return shipment


def shipped_bushels(
    uow: UnitOfWork, commodity_code: str, through: date, origin_code: str | None = None
) -> Decimal:
    """Everything loaded out, for the position report."""
    query = (
        select(func.sum(ShipmentRow.origin_bushels))
        .join(SaleRow, SaleRow.id == ShipmentRow.sale_id)
        .where(
            SaleRow.commodity_code == commodity_code,
            ShipmentRow.shipped_on <= through,
            ShipmentRow.rejected.is_(False),
        )
    )
    if origin_code:
        query = query.where(SaleRow.origin_code == origin_code)
    return quantize(as_decimal(uow.session.scalar(query)), BUSHEL_PLACES)


def sold_forward(
    uow: UnitOfWork, commodity_code: str, on: date, origin_code: str | None = None
) -> Decimal:
    """Bushels sold and not yet loaded, which is what makes the house short."""
    outstanding = Decimal("0")
    for row in open_sales(uow, commodity_code, on):
        if origin_code and row.origin_code != origin_code:
            continue
        left = as_decimal(row.bushels) - as_decimal(row.shipped)
        if left > 0:
            outstanding += left
    return quantize(outstanding, BUSHEL_PLACES)


def transit_loss(uow: UnitOfWork, sale_number: str) -> Decimal:
    """Bushels that went missing on the way, across every settled car."""
    row = sale(uow, sale_number)
    losses = [
        shipment.transit_shrink
        for shipment in row.shipments
        if shipment.transit_shrink is not None
    ]
    return quantize(sum(losses, Decimal("0")), BUSHEL_PLACES)


def freight_owed(uow: UnitOfWork, sale_number: str) -> Decimal:
    """Freight on everything loaded out against a sale."""
    row = sale(uow, sale_number)
    return extend(as_decimal(row.shipped), as_decimal(row.freight_per_bushel))
