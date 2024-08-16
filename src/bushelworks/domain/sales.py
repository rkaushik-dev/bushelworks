"""Sales out of the house, which is the other half of the position.

A purchase contract is grain the house has bought from a producer. A sale is
grain it has sold to a terminal, a processor or a feedlot, and the two are not
mirror images:

* a sale is shipped rather than delivered, so it is measured at destination and
  the house carries the shrink and the freight in between
* a sale has a quality specification with a rejection clause, not a discount
  schedule. The buyer can refuse a car, and a refused car is still the house's
* a sale is priced against the same futures month but on the other side, so the
  basis is the house's margin rather than its cost

Shipping tolerance works the other way round too. A purchase contract lets the
producer come in light; a sale contract obliges the house to load the full
amount, and the tolerance is the buyer's to use.
"""

from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import date
from decimal import Decimal
from enum import Enum

from ..errors import ValidationError
from ..money import PRICE_PLACES, price
from ..units import BUSHEL_PLACES, quantize
from .contracts import DeliveryWindow
from .pricing import FuturesMonth


class SaleKind(str, Enum):
    SPOT = "spot"
    FORWARD = "forward"
    BASIS = "basis"


class Destination(str, Enum):
    TERMINAL = "terminal"
    PROCESSOR = "processor"
    FEEDLOT = "feedlot"
    EXPORT = "export"


class Carrier(str, Enum):
    TRUCK = "truck"
    RAIL = "rail"
    BARGE = "barge"


class SaleError(ValidationError):
    """Raised when a sale cannot stand as written."""

    code = "sale_error"


@dataclass(frozen=True, slots=True)
class Specification:
    """What the buyer will accept, and what lets him refuse."""

    max_moisture: Decimal
    min_test_weight: Decimal
    max_damage: Decimal
    max_foreign_material: Decimal
    reject_on_odour: bool = True

    def __post_init__(self) -> None:
        if self.max_moisture <= 0 or self.max_moisture >= 100:
            raise SaleError("moisture specification out of range")
        if self.min_test_weight <= 0:
            raise SaleError("test weight specification out of range")

    def failures(self, sample: dict) -> tuple[str, ...]:
        """Which clauses this load breaks, named the way the contract names them."""
        from .commodity import Factor

        broken: list[str] = []
        moisture = sample.get(Factor.MOISTURE)
        if moisture is not None and moisture > self.max_moisture:
            broken.append("moisture")
        test_weight = sample.get(Factor.TEST_WEIGHT)
        if test_weight is not None and test_weight < self.min_test_weight:
            broken.append("test weight")
        damage = sample.get(Factor.DAMAGE_TOTAL)
        if damage is not None and damage > self.max_damage:
            broken.append("damage")
        foreign = sample.get(Factor.FOREIGN_MATERIAL) or sample.get(Factor.BCFM)
        if foreign is not None and foreign > self.max_foreign_material:
            broken.append("foreign material")
        return tuple(broken)

    def accepts(self, sample: dict) -> bool:
        return not self.failures(sample)


@dataclass(frozen=True, slots=True)
class SaleContract:
    """One sale, with what has shipped against it."""

    number: str
    kind: SaleKind
    buyer: str
    destination: Destination
    commodity_code: str
    origin_code: str
    written_on: date
    bushels: Decimal
    window: DeliveryWindow
    specification: Specification
    carrier: Carrier = Carrier.TRUCK
    futures_month: FuturesMonth | None = None
    futures_price: Decimal | None = None
    basis: Decimal | None = None
    flat_price: Decimal | None = None
    tolerance_percent: Decimal = Decimal("2")
    freight_per_bushel: Decimal = Decimal("0")
    shipped: Decimal = Decimal("0")

    def __post_init__(self) -> None:
        if not self.number:
            raise SaleError("a sale needs a number")
        if self.bushels <= 0:
            raise SaleError(f"{self.number}: a sale needs bushels")
        if self.shipped < 0:
            raise SaleError(f"{self.number}: shipped cannot be negative")
        if self.tolerance_percent < 0 or self.tolerance_percent > Decimal("10"):
            raise SaleError(f"{self.number}: tolerance out of range")
        if self.freight_per_bushel < 0:
            raise SaleError(f"{self.number}: freight cannot be negative")
        if self.kind is SaleKind.BASIS and self.basis is None:
            raise SaleError(f"{self.number}: a basis sale needs a basis")
        if self.kind is not SaleKind.BASIS and self.flat_price is None:
            if self.futures_price is None or self.basis is None:
                raise SaleError(f"{self.number}: a {self.kind.value} sale needs a price")

    @property
    def is_priced(self) -> bool:
        if self.flat_price is not None:
            return True
        return self.futures_price is not None and self.basis is not None

    @property
    def unit_price(self) -> Decimal:
        if self.flat_price is not None:
            return price(self.flat_price)
        if self.futures_price is None or self.basis is None:
            raise SaleError(f"{self.number}: sale is not priced yet")
        return quantize(
            Decimal(self.futures_price) + Decimal(self.basis), PRICE_PLACES
        )

    @property
    def net_price(self) -> Decimal:
        """What the house keeps after freight."""
        return quantize(self.unit_price - self.freight_per_bushel, PRICE_PLACES)

    @property
    def remaining(self) -> Decimal:
        """Bushels still to load, floored at zero."""
        left = self.bushels - self.shipped
        return quantize(left, BUSHEL_PLACES) if left > 0 else Decimal("0.0000")

    @property
    def maximum_bushels(self) -> Decimal:
        ceiling = self.bushels * (Decimal("100") + self.tolerance_percent) / Decimal("100")
        return quantize(ceiling, BUSHEL_PLACES)

    @property
    def is_complete(self) -> bool:
        """Complete means loaded to the face amount, not to the tolerance.

        The house owes the whole number. The tolerance is there for the last
        car being a little heavy, not for coming up short.
        """
        return self.shipped >= self.bushels

    def can_ship(self, bushels: Decimal, on: date) -> bool:
        if not self.window.contains(on):
            return False
        return self.shipped + Decimal(bushels) <= self.maximum_bushels

    def ship(self, bushels: Decimal, on: date) -> "SaleContract":
        wanted = quantize(Decimal(bushels), BUSHEL_PLACES)
        if wanted <= 0:
            raise SaleError(f"{self.number}: cannot ship {wanted} bushels")
        if not self.window.contains(on):
            raise SaleError(
                f"{self.number}: {on} is outside the shipping window"
            )
        if self.shipped + wanted > self.maximum_bushels:
            raise SaleError(
                f"{self.number}: shipping {wanted} would take it past "
                f"{self.maximum_bushels}"
            )
        return replace(self, shipped=quantize(self.shipped + wanted, BUSHEL_PLACES))

    def set_futures(self, futures: Decimal) -> "SaleContract":
        if self.kind is not SaleKind.BASIS:
            raise SaleError(f"{self.number}: only a basis sale sets futures later")
        if self.futures_price is not None:
            raise SaleError(f"{self.number}: futures are already set")
        return replace(self, futures_price=price(futures))
