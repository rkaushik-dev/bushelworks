"""Storage charges on grain the house is holding for someone else.

Three accrual conventions are in the wild and a house uses exactly one, filed
with the state along with the rate:

* ``DAILY`` prorates the monthly rate over the days actually stored.
* ``ANNIVERSARY`` charges a whole month every time the day-of-month the grain
  arrived comes round again, so a bin emptied one day late pays for a month.
* ``CALENDAR`` charges a whole month for every calendar month touched, which
  makes grain delivered on the thirty first of October pay for October.

The free period comes off the front under every convention and it is counted in
days, never in months. The in-charge is a separate per bushel fee taken once at
receipt and it is not storage at all, though every producer calls it that.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from decimal import Decimal, ROUND_CEILING
from enum import Enum

from ..money import amount, extend
from ..units import BUSHEL_PLACES, quantize

DAYS_PER_MONTH = Decimal("30")


class AccrualMethod(str, Enum):
    DAILY = "daily"
    ANNIVERSARY = "anniversary"
    CALENDAR = "calendar"


class StorageError(ValueError):
    """Raised when a storage charge cannot be worked out as configured."""


@dataclass(frozen=True, slots=True)
class StorageRate:
    """One filed storage tariff."""

    commodity_code: str
    rate_per_bushel_per_month: Decimal
    method: AccrualMethod = AccrualMethod.DAILY
    free_days: int = 0
    in_charge_per_bushel: Decimal = Decimal("0")
    minimum_charge: Decimal = Decimal("0.00")

    def __post_init__(self) -> None:
        if self.rate_per_bushel_per_month < 0:
            raise StorageError(f"{self.commodity_code}: storage rate cannot be negative")
        if self.free_days < 0:
            raise StorageError(f"{self.commodity_code}: free days cannot be negative")
        if self.in_charge_per_bushel < 0:
            raise StorageError(f"{self.commodity_code}: in charge cannot be negative")

    def chargeable_days(self, stored_on: date, through: date) -> int:
        """Days of storage after the free period, never below zero."""
        if through < stored_on:
            raise StorageError("storage cannot be charged to a date before receipt")
        return max((through - stored_on).days - self.free_days, 0)

    def chargeable_months(self, stored_on: date, through: date) -> Decimal:
        """Months to charge, in whatever unit this method counts in."""
        days = self.chargeable_days(stored_on, through)
        if days <= 0:
            return Decimal("0")
        if self.method is AccrualMethod.DAILY:
            return quantize(Decimal(days) / DAYS_PER_MONTH, Decimal("0.000001"))
        if self.method is AccrualMethod.ANNIVERSARY:
            return (Decimal(days) / DAYS_PER_MONTH).quantize(
                Decimal("1"), rounding=ROUND_CEILING
            )
        return Decimal(_calendar_months(stored_on, through, self.free_days))

    def charge(self, bushels: Decimal, stored_on: date, through: date) -> Decimal:
        """Dollars of storage owed on this lot as of ``through``."""
        if bushels < 0:
            raise StorageError("cannot charge storage on negative bushels")
        months = self.chargeable_months(stored_on, through)
        if months <= 0:
            return amount("0.00")
        gross = extend(Decimal(bushels), self.rate_per_bushel_per_month * months)
        return amount(max(gross, self.minimum_charge))

    def in_charge(self, bushels: Decimal) -> Decimal:
        return extend(Decimal(bushels), self.in_charge_per_bushel)


def _calendar_months(stored_on: date, through: date, free_days: int) -> int:
    """Whole calendar months touched, counted from the end of the free period."""
    start = _add_days(stored_on, free_days)
    if through < start:
        return 0
    months = (through.year - start.year) * 12 + (through.month - start.month) + 1
    return max(months, 1)


def _add_days(day: date, days: int) -> date:
    from datetime import timedelta

    return day + timedelta(days=days)


@dataclass(frozen=True, slots=True)
class StorageLot:
    """Bushels of one producer's grain, received on one day."""

    lot_id: str
    party_id: str
    commodity_code: str
    location_code: str
    bushels: Decimal
    stored_on: date
    withdrawn: Decimal = Decimal("0")

    def __post_init__(self) -> None:
        if self.bushels <= 0:
            raise StorageError(f"{self.lot_id}: a storage lot needs bushels")
        if self.withdrawn < 0:
            raise StorageError(f"{self.lot_id}: withdrawals cannot be negative")
        if self.withdrawn > self.bushels:
            raise StorageError(
                f"{self.lot_id}: withdrawn {self.withdrawn} exceeds the lot"
            )

    @property
    def open_bushels(self) -> Decimal:
        return quantize(self.bushels - self.withdrawn, BUSHEL_PLACES)

    @property
    def is_empty(self) -> bool:
        return self.open_bushels == 0

    def withdraw(self, bushels: Decimal) -> "StorageLot":
        from dataclasses import replace

        wanted = quantize(Decimal(bushels), BUSHEL_PLACES)
        if wanted <= 0:
            raise StorageError(f"{self.lot_id}: cannot withdraw {wanted} bushels")
        if wanted > self.open_bushels:
            raise StorageError(
                f"{self.lot_id}: only {self.open_bushels} bushels are open"
            )
        return replace(self, withdrawn=quantize(self.withdrawn + wanted, BUSHEL_PLACES))

    def accrued(self, rate: StorageRate, through: date) -> Decimal:
        return rate.charge(self.open_bushels, self.stored_on, through)


def draw_down(
    lots: list[StorageLot], bushels: Decimal, oldest_first: bool = True
) -> tuple[list[StorageLot], list[tuple[str, Decimal]]]:
    """Take bushels out of a producer's lots, oldest first by default.

    First in first out is what the warehouse licence assumes and what keeps the
    storage bill honest. A house that draws newest first is charging storage on
    bushels that left, and the state auditor calls that what it is.
    """
    wanted = quantize(Decimal(bushels), BUSHEL_PLACES)
    if wanted <= 0:
        raise StorageError("nothing to draw down")
    available = sum((lot.open_bushels for lot in lots), Decimal("0"))
    if wanted > available:
        raise StorageError(f"only {available} bushels on hand, asked for {wanted}")

    ordered = sorted(lots, key=lambda lot: lot.stored_on, reverse=not oldest_first)
    taken: list[tuple[str, Decimal]] = []
    updated: dict[str, StorageLot] = {lot.lot_id: lot for lot in lots}

    for lot in ordered:
        if wanted <= 0:
            break
        current = updated[lot.lot_id]
        take = min(current.open_bushels, wanted)
        if take <= 0:
            continue
        updated[lot.lot_id] = current.withdraw(take)
        taken.append((lot.lot_id, take))
        wanted = quantize(wanted - take, BUSHEL_PLACES)

    return [updated[lot.lot_id] for lot in lots], taken
