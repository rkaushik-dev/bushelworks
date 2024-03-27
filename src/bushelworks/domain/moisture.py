"""Moisture shrink and the drying charge that goes with it.

Wet grain is sold by the elevator dry, so the water has to come off the bushel
count before anything is priced. There are two conventions and a house picks one
and never mixes them:

* the *water* method, which removes only the weight of the water and is what the
  arithmetic actually supports, and
* the *table* method, which multiplies the points of moisture by a published
  shrink factor slightly above the water figure so the number also covers the
  handling loss through the dryer.

Both are here because both are in use on the same driveway: a house will shrink
its own storage on water and its purchases on the table. Getting this wrong
shows up as a slow inventory drift that nobody can find for two years.

Shrink and drying are separate charges. Shrink takes bushels away, drying takes
money away, and a load can be subject to one, both or neither.
"""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from enum import Enum
from typing import Sequence

from ..money import PRICE_PLACES, amount, extend
from ..units import BUSHEL_PLACES, quantize
from ..errors import ValidationError


class ShrinkMethod(str, Enum):
    WATER = "water"
    TABLE = "table"
    NONE = "none"


class MoistureError(ValidationError):
    """Raised when a moisture reading cannot be shrunk as configured."""


PERCENT_PLACES = Decimal("0.0001")


@dataclass(frozen=True, slots=True)
class ShrinkSchedule:
    """How one commodity's water comes off the bushel count."""

    commodity_code: str
    base_moisture: Decimal
    method: ShrinkMethod = ShrinkMethod.TABLE
    shrink_factor: Decimal = Decimal("1.18")
    handling_loss_percent: Decimal = Decimal("0")
    reject_above: Decimal | None = None

    def __post_init__(self) -> None:
        if not (Decimal("0") < self.base_moisture < Decimal("100")):
            raise ValueError(f"{self.commodity_code}: base moisture out of range")
        if self.shrink_factor < 0:
            raise ValueError(f"{self.commodity_code}: shrink factor cannot be negative")
        if self.handling_loss_percent < 0:
            raise ValueError(f"{self.commodity_code}: handling loss cannot be negative")

    def points_over(self, moisture: Decimal) -> Decimal:
        """Points of moisture above base, floored at zero.

        Dry grain does not shrink upward. A load at 12.4 against a 15.0 base is
        zero points, not minus 2.6, and the premium some houses pay for dry corn
        is a separate line item rather than a negative shrink.
        """
        over = Decimal(moisture) - self.base_moisture
        return over if over > 0 else Decimal("0")

    def shrink_percent(self, moisture: Decimal) -> Decimal:
        """Percentage of the wet bushels that shrink away."""
        m = Decimal(moisture)
        if m < 0 or m >= 100:
            raise MoistureError(f"moisture out of range: {m}")
        if self.reject_above is not None and m > self.reject_above:
            raise MoistureError(
                f"{self.commodity_code}: {m}% is above the {self.reject_above}% limit"
            )
        points = self.points_over(m)
        if points == 0 or self.method is ShrinkMethod.NONE:
            return Decimal("0.0000")
        if self.method is ShrinkMethod.WATER:
            water = points / (Decimal("100") - self.base_moisture) * Decimal("100")
            return quantize(water + self.handling_loss_percent, PERCENT_PLACES)
        return quantize(points * self.shrink_factor, PERCENT_PLACES)

    def dry_bushels(self, wet_bushels: Decimal, moisture: Decimal) -> Decimal:
        """Wet bushels less the shrink, at bushel precision."""
        pct = self.shrink_percent(moisture)
        kept = (Decimal("100") - pct) / Decimal("100")
        return quantize(Decimal(wet_bushels) * kept, BUSHEL_PLACES)

    def shrunk_bushels(self, wet_bushels: Decimal, moisture: Decimal) -> Decimal:
        """The bushels that disappeared. Wet less dry, so the two always add up."""
        return quantize(
            Decimal(wet_bushels) - self.dry_bushels(wet_bushels, moisture),
            BUSHEL_PLACES,
        )


@dataclass(frozen=True, slots=True)
class DryingTier:
    """Cents per point per bushel over a band of points."""

    up_to_points: Decimal | None
    rate_per_point: Decimal

    def __post_init__(self) -> None:
        if self.up_to_points is not None and self.up_to_points <= 0:
            raise ValueError("a drying tier has to cover at least one point")
        if self.rate_per_point < 0:
            raise ValueError("a drying rate cannot be negative")


class DryingBasis(str, Enum):
    """Which bushel count the per point rate is applied to."""

    WET = "wet"
    DRY = "dry"


@dataclass(frozen=True, slots=True)
class DryingSchedule:
    """A tiered drying charge.

    Tiers are cumulative bands, not a lookup: a load four points over a schedule
    with a two point first tier pays the first tier on two points and the second
    tier on the other two. Houses that quote a flat rate simply publish one tier
    with no ceiling.
    """

    commodity_code: str
    tiers: Sequence[DryingTier]
    basis: DryingBasis = DryingBasis.WET
    minimum_charge: Decimal = Decimal("0.00")
    free_points: Decimal = Decimal("0")

    def __post_init__(self) -> None:
        if not self.tiers:
            raise ValueError(f"{self.commodity_code}: drying schedule has no tiers")
        seen_open = False
        last = Decimal("0")
        for tier in self.tiers:
            if seen_open:
                raise ValueError(
                    f"{self.commodity_code}: a tier follows the open ended tier"
                )
            if tier.up_to_points is None:
                seen_open = True
                continue
            if tier.up_to_points <= last:
                raise ValueError(f"{self.commodity_code}: drying tiers are out of order")
            last = tier.up_to_points

    def rate_for(self, points: Decimal) -> Decimal:
        """Blended dollars per bushel for this many chargeable points."""
        chargeable = Decimal(points) - self.free_points
        if chargeable <= 0:
            return Decimal("0.0000")
        remaining = chargeable
        consumed = Decimal("0")
        total = Decimal("0")
        for tier in self.tiers:
            if remaining <= 0:
                break
            if tier.up_to_points is None:
                band = remaining
            else:
                band = min(remaining, tier.up_to_points - consumed)
                if band <= 0:
                    continue
            total += band * tier.rate_per_point
            remaining -= band
            consumed += band
        return quantize(total, PRICE_PLACES)

    def charge(self, bushels: Decimal, points: Decimal) -> Decimal:
        """Dollars owed for drying this load."""
        gross = extend(Decimal(bushels), self.rate_for(points))
        if gross == 0:
            return amount("0.00")
        return amount(max(gross, self.minimum_charge))
