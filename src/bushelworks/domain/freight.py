"""Trucking, and who pays for it.

Freight is quoted three ways and the house sees all three in a week:

* per loaded mile, which is what an owner operator quotes
* per bushel, which is what the terminal quotes back
* per hundredweight, which is what the feedlot quotes because it buys by weight

They are not interchangeable. A per mile rate on a short haul with a light load
is dearer per bushel than the same rate on a long haul with a heavy one, and a
house that converts one to another without the distance and the weight in front
of it will be wrong every time.

Fuel surcharge is a percentage of the line haul and it moves weekly. It is a
separate figure rather than folded into the rate because the carrier's invoice
shows it separately and the two have to reconcile.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from decimal import Decimal
from enum import Enum

from ..errors import ValidationError
from ..money import PRICE_PLACES, amount, extend
from ..units import POUNDS_PER_CWT, quantize

# What a hopper bottom holds. Used only to sanity check a quoted load.
TYPICAL_HOPPER_POUNDS = Decimal("52000")


class RateBasis(str, Enum):
    PER_MILE = "per_mile"
    PER_BUSHEL = "per_bushel"
    PER_HUNDREDWEIGHT = "per_cwt"
    FLAT = "flat"


class PaidBy(str, Enum):
    HOUSE = "house"
    PRODUCER = "producer"
    BUYER = "buyer"


class FreightError(ValidationError):
    """Raised when a haul cannot be rated."""

    code = "freight_error"


@dataclass(frozen=True, slots=True)
class Lane:
    """One origin to one destination, with the distance the carrier is paid on."""

    origin: str
    destination: str
    loaded_miles: Decimal
    is_round_trip: bool = False

    def __post_init__(self) -> None:
        if self.loaded_miles <= 0:
            raise FreightError(f"{self.origin} to {self.destination}: no distance")
        if self.origin == self.destination:
            raise FreightError("a lane has to go somewhere")

    @property
    def billed_miles(self) -> Decimal:
        """Miles the carrier bills. A round trip lane bills both ways."""
        return self.loaded_miles * (Decimal("2") if self.is_round_trip else Decimal("1"))

    @property
    def label(self) -> str:
        return f"{self.origin}-{self.destination}"


@dataclass(frozen=True, slots=True)
class FreightRate:
    """One carrier's quote on one lane, with its fuel surcharge."""

    carrier: str
    lane: Lane
    basis: RateBasis
    rate: Decimal
    effective_from: date
    fuel_surcharge_percent: Decimal = Decimal("0")
    minimum_charge: Decimal = Decimal("0.00")
    paid_by: PaidBy = PaidBy.HOUSE

    def __post_init__(self) -> None:
        if self.rate < 0:
            raise FreightError(f"{self.carrier}: a rate cannot be negative")
        if self.fuel_surcharge_percent < 0 or self.fuel_surcharge_percent > Decimal("60"):
            raise FreightError(f"{self.carrier}: fuel surcharge out of range")
        if self.minimum_charge < 0:
            raise FreightError(f"{self.carrier}: a minimum cannot be negative")

    def line_haul(self, bushels: Decimal, pounds: Decimal) -> Decimal:
        """The charge before fuel, on whatever basis this rate is quoted."""
        if bushels < 0 or pounds < 0:
            raise FreightError(f"{self.carrier}: cannot rate a negative load")
        if self.basis is RateBasis.FLAT:
            return amount(self.rate)
        if self.basis is RateBasis.PER_MILE:
            return amount(self.rate * self.lane.billed_miles)
        if self.basis is RateBasis.PER_BUSHEL:
            return extend(bushels, self.rate)
        return extend(quantize(pounds / POUNDS_PER_CWT, Decimal("0.01")), self.rate)

    def fuel(self, bushels: Decimal, pounds: Decimal) -> Decimal:
        if self.fuel_surcharge_percent == 0:
            return amount("0.00")
        base = self.line_haul(bushels, pounds)
        return amount(base * self.fuel_surcharge_percent / Decimal("100"))

    def total(self, bushels: Decimal, pounds: Decimal) -> Decimal:
        """Line haul plus fuel, floored at the minimum."""
        charge = amount(self.line_haul(bushels, pounds) + self.fuel(bushels, pounds))
        return amount(max(charge, self.minimum_charge))

    def per_bushel(self, bushels: Decimal, pounds: Decimal) -> Decimal:
        """What this haul works out to per bushel, for comparing quotes.

        The only honest way to compare a per mile quote with a per bushel one,
        and it needs a real load to do it.
        """
        if bushels <= 0:
            raise FreightError(f"{self.carrier}: cannot rate per bushel on no bushels")
        return quantize(self.total(bushels, pounds) / bushels, PRICE_PLACES)


@dataclass(frozen=True, slots=True)
class Haul:
    """One truck, rated."""

    reference: str
    rate: FreightRate
    bushels: Decimal
    pounds: Decimal
    hauled_on: date

    @property
    def line_haul(self) -> Decimal:
        return self.rate.line_haul(self.bushels, self.pounds)

    @property
    def fuel(self) -> Decimal:
        return self.rate.fuel(self.bushels, self.pounds)

    @property
    def total(self) -> Decimal:
        return self.rate.total(self.bushels, self.pounds)

    @property
    def is_overweight(self) -> bool:
        """Whether the load is heavier than a hopper bottom usually carries.

        Not an error. It flags a load worth looking at before the scale house
        argues with the carrier's invoice.
        """
        return self.pounds > TYPICAL_HOPPER_POUNDS * Decimal("1.15")


def cheapest(
    rates: list[FreightRate], bushels: Decimal, pounds: Decimal
) -> FreightRate:
    """The cheapest quote for a real load, compared per bushel."""
    if not rates:
        raise FreightError("no rates to compare")
    return min(rates, key=lambda rate: rate.per_bushel(bushels, pounds))


def invoice_total(hauls: list[Haul]) -> Decimal:
    return amount(sum((haul.total for haul in hauls), Decimal("0")))


def reconcile_invoice(hauls: list[Haul], invoiced: Decimal) -> Decimal:
    """The gap between what the carrier billed and what the rates say.

    Positive means the carrier billed more than the rate. That is the one the
    office chases, and it is almost always a fuel surcharge from the wrong week.
    """
    return amount(Decimal(invoiced) - invoice_total(hauls))
