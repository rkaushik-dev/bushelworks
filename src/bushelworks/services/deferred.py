"""Deferred price grain, which the trade calls price later.

Title passes at delivery and the price is agreed some time afterwards. The
producer is an unsecured creditor of the house for the whole time, which is why
several states cap how long the arrangement can run and why the house charges a
service fee for it.

Three things make this different from storage and all three catch people out:

* the house owns the bushels, so they are on its position and have to be hedged
* the producer is owed money rather than grain, so a withdrawal is a pricing
  rather than a load out
* the service fee accrues on a different clock from storage, usually with no
  free period at all
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from decimal import Decimal

from sqlalchemy import select

from ..errors import ConflictError, ValidationError
from ..money import amount, extend
from ..persistence.base import as_decimal
from ..persistence.models import StorageLotRow
from ..persistence.unitofwork import UnitOfWork
from ..units import BUSHEL_PLACES, quantize
from .warehousing import lot, open_lots


class DeferredPriceError(ValidationError):
    """Raised when a price later arrangement cannot be worked as asked."""

    code = "deferred_price_error"


@dataclass(frozen=True, slots=True)
class ServiceTerms:
    """What the house charges for carrying an unpriced obligation."""

    rate_per_bushel_per_month: Decimal
    free_days: int = 0
    maximum_days: int | None = None

    def __post_init__(self) -> None:
        if self.rate_per_bushel_per_month < 0:
            raise DeferredPriceError("a service rate cannot be negative")
        if self.free_days < 0:
            raise DeferredPriceError("free days cannot be negative")
        if self.maximum_days is not None and self.maximum_days < 1:
            raise DeferredPriceError("a maximum term has to be at least a day")

    def chargeable_days(self, delivered_on: date, through: date) -> int:
        if through < delivered_on:
            raise DeferredPriceError("cannot charge back before delivery")
        return max((through - delivered_on).days - self.free_days, 0)

    def is_overdue(self, delivered_on: date, on: date) -> bool:
        if self.maximum_days is None:
            return False
        return (on - delivered_on).days > self.maximum_days

    def charge(self, bushels: Decimal, delivered_on: date, through: date) -> Decimal:
        """Service fee owed, prorated by the day over a thirty day month."""
        days = self.chargeable_days(delivered_on, through)
        if days <= 0:
            return amount("0.00")
        months = Decimal(days) / Decimal("30")
        return extend(Decimal(bushels), self.rate_per_bushel_per_month * months)


DEFAULT_TERMS = ServiceTerms(
    rate_per_bushel_per_month=Decimal("0.0300"),
    free_days=0,
    maximum_days=365,
)


@dataclass(frozen=True, slots=True)
class DeferredPosition:
    """What a producer has unpriced, and what it has cost him so far."""

    party_code: str
    commodity_code: str
    bushels: Decimal
    oldest_delivery: date
    service_owed: Decimal
    overdue_lots: tuple[str, ...]

    @property
    def is_overdue(self) -> bool:
        return bool(self.overdue_lots)


def deliver(
    uow: UnitOfWork,
    lot_id: str,
    party_code: str,
    commodity_code: str,
    location_code: str,
    bushels: Decimal,
    delivered_on: date,
    ticket_id: int | None = None,
) -> StorageLotRow:
    """Take delivery of grain the producer has not priced.

    Written as a lot with the deferred flag set rather than as a separate table.
    The bushels behave the same way going out; what differs is which side of the
    position they sit on and what they are charged.
    """
    from .warehousing import store

    return store(
        uow,
        lot_id=lot_id,
        party_code=party_code,
        commodity_code=commodity_code,
        location_code=location_code,
        bushels=bushels,
        stored_on=delivered_on,
        ticket_id=ticket_id,
        deferred_price=True,
    )


def unpriced_lots(
    uow: UnitOfWork,
    party_code: str | None = None,
    commodity_code: str | None = None,
    location_code: str | None = None,
) -> list[StorageLotRow]:
    return [
        row
        for row in open_lots(uow, party_code, commodity_code, location_code)
        if row.is_deferred_price
    ]


def price_lot(
    uow: UnitOfWork,
    lot_id: str,
    bushels: Decimal,
    unit_price: Decimal,
    priced_on: date,
    terms: ServiceTerms = DEFAULT_TERMS,
) -> tuple[Decimal, Decimal]:
    """Fix a price on some or all of a deferred lot.

    Returns the gross value and the service fee, which the settlement builder
    puts on separate lines. The fee is charged on the bushels being priced
    rather than on the whole lot, because a producer who prices half in March
    should not pay March's fee on the half he leaves until July.
    """
    row = lot(uow, lot_id)
    if not row.is_deferred_price:
        raise DeferredPriceError(f"{lot_id} is storage, not deferred price")
    wanted = quantize(Decimal(bushels), BUSHEL_PLACES)
    if wanted <= 0:
        raise DeferredPriceError(f"{lot_id}: nothing to price")
    if wanted > row.open_bushels:
        raise DeferredPriceError(
            f"{lot_id} has {row.open_bushels} bushels open, asked to price {wanted}"
        )
    if unit_price <= 0:
        raise DeferredPriceError(f"{lot_id}: a price has to be positive")
    if priced_on < row.stored_on:
        raise ConflictError(f"{lot_id}: cannot price before it was delivered")

    gross = extend(wanted, unit_price)
    fee = terms.charge(wanted, row.stored_on, priced_on)
    return gross, fee


def position(
    uow: UnitOfWork,
    party_code: str,
    commodity_code: str,
    as_of: date,
    terms: ServiceTerms = DEFAULT_TERMS,
) -> DeferredPosition | None:
    """What one producer has unpriced in one commodity."""
    rows = unpriced_lots(uow, party_code, commodity_code)
    if not rows:
        return None
    bushels = quantize(
        sum((row.open_bushels for row in rows), Decimal("0")), BUSHEL_PLACES
    )
    owed = amount(
        sum(
            (terms.charge(row.open_bushels, row.stored_on, as_of) for row in rows),
            Decimal("0"),
        )
    )
    overdue = tuple(
        row.lot_id for row in rows if terms.is_overdue(row.stored_on, as_of)
    )
    return DeferredPosition(
        party_code=party_code,
        commodity_code=commodity_code,
        bushels=bushels,
        oldest_delivery=min(row.stored_on for row in rows),
        service_owed=owed,
        overdue_lots=overdue,
    )


def house_exposure(uow: UnitOfWork, commodity_code: str, as_of: date) -> Decimal:
    """Bushels the house owns because nobody has priced them yet.

    This is the number that has to be hedged and the one most often missed. The
    grain is in the bin, it is not the producer's any more, and if the house
    counts it as storage it is long by that much without knowing.
    """
    rows = unpriced_lots(uow, commodity_code=commodity_code)
    return quantize(
        sum(
            (row.open_bushels for row in rows if row.stored_on <= as_of),
            Decimal("0"),
        ),
        BUSHEL_PLACES,
    )


def overdue(
    uow: UnitOfWork, as_of: date, terms: ServiceTerms = DEFAULT_TERMS
) -> list[StorageLotRow]:
    """Every deferred lot past the term the state allows."""
    return [
        row
        for row in unpriced_lots(uow)
        if terms.is_overdue(row.stored_on, as_of)
    ]


def total_unpriced(uow: UnitOfWork) -> Decimal:
    rows = uow.session.scalars(
        select(StorageLotRow).where(
            StorageLotRow.is_deferred_price.is_(True),
            StorageLotRow.closed_on.is_(None),
        )
    )
    return quantize(
        sum((as_decimal(row.bushels) - row.withdrawn for row in rows), Decimal("0")),
        BUSHEL_PLACES,
    )
