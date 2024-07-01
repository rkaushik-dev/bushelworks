"""Factor discount schedules.

A discount schedule turns one grade factor into cents per bushel. Every house
publishes its own and they are all the same shape: a threshold where the
discount starts, an increment it is charged in, and a rate per increment. What
differs, and what quietly costs money when it is got wrong, is how the increment
is counted.

* ``STEP`` charges a whole increment the moment any part of it is crossed, so
  0.1 over on a 0.5 increment costs the same as 0.5 over. This is what a printed
  discount table means when it says "per half pound or fraction thereof".
* ``PRORATE`` charges the exact fraction. Rare on the driveway, common in the
  contract with the terminal.
* ``FLAT`` charges the rate once, no matter how far over the load is.

Bands stack. A test weight schedule that charges one rate to 52 pounds and a
steeper one below that charges the first rate across the first band and the
steeper rate only on the part below 52, which is not what most people guess
when they read the table.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from decimal import ROUND_CEILING, Decimal
from enum import Enum

from ..errors import ValidationError
from ..money import PRICE_PLACES, extend
from ..units import quantize
from .commodity import FACTOR_DIRECTION, Factor, FactorDirection


class DiscountMode(str, Enum):
    STEP = "step"
    PRORATE = "prorate"
    FLAT = "flat"


class ScheduleError(ValidationError):
    """Raised when a schedule is not usable as written."""


@dataclass(frozen=True, slots=True)
class DiscountBand:
    """One band of a schedule, measured from ``start`` toward the bad end."""

    start: Decimal
    rate: Decimal
    increment: Decimal = Decimal("1")
    mode: DiscountMode = DiscountMode.STEP
    end: Decimal | None = None

    def __post_init__(self) -> None:
        if self.increment <= 0:
            raise ScheduleError("a discount increment must be positive")
        if self.rate < 0:
            raise ScheduleError("a discount rate cannot be negative")

    def width(self, direction: FactorDirection) -> Decimal | None:
        if self.end is None:
            return None
        if direction is FactorDirection.LOW_IS_BAD:
            return self.start - self.end
        return self.end - self.start


@dataclass(frozen=True, slots=True)
class DiscountSchedule:
    """The whole ladder for one factor of one commodity."""

    commodity_code: str
    factor: Factor
    bands: Sequence[DiscountBand]
    label: str = ""

    def __post_init__(self) -> None:
        if not self.bands:
            raise ScheduleError(f"{self.commodity_code}/{self.factor.value}: no bands")
        direction = self.direction
        previous: DiscountBand | None = None
        for band in self.bands:
            if previous is not None:
                if previous.end is None:
                    raise ScheduleError(
                        f"{self.commodity_code}/{self.factor.value}: "
                        "a band follows an open ended band"
                    )
                if band.start != previous.end:
                    raise ScheduleError(
                        f"{self.commodity_code}/{self.factor.value}: "
                        f"gap or overlap at {band.start}"
                    )
            if band.end is not None:
                if direction is FactorDirection.LOW_IS_BAD and band.end >= band.start:
                    raise ScheduleError(
                        f"{self.commodity_code}/{self.factor.value}: "
                        "a low is bad band has to descend"
                    )
                if direction is FactorDirection.HIGH_IS_BAD and band.end <= band.start:
                    raise ScheduleError(
                        f"{self.commodity_code}/{self.factor.value}: "
                        "a high is bad band has to ascend"
                    )
            previous = band

    @property
    def direction(self) -> FactorDirection:
        return FACTOR_DIRECTION[self.factor]

    @property
    def threshold(self) -> Decimal:
        """The value at which this schedule starts costing anything."""
        return self.bands[0].start

    def excess(self, value: Decimal) -> Decimal:
        """How far past the threshold this reading is, floored at zero."""
        v = Decimal(value)
        if self.direction is FactorDirection.LOW_IS_BAD:
            over = self.threshold - v
        else:
            over = v - self.threshold
        return over if over > 0 else Decimal("0")

    def rate_for(self, value: Decimal) -> Decimal:
        """Dollars per bushel of discount for this factor reading."""
        remaining = self.excess(value)
        if remaining <= 0:
            return Decimal("0.0000")

        total = Decimal("0")
        for band in self.bands:
            if remaining <= 0:
                break
            width = band.width(self.direction)
            span = remaining if width is None else min(remaining, width)
            if span <= 0:
                continue
            total += _band_charge(band, span)
            remaining -= span
        return quantize(total, PRICE_PLACES)

    def charge(self, bushels: Decimal, value: Decimal) -> Decimal:
        """Dollars of discount on a load of this size."""
        return extend(Decimal(bushels), self.rate_for(value))


def _band_charge(band: DiscountBand, span: Decimal) -> Decimal:
    if band.mode is DiscountMode.FLAT:
        return band.rate
    steps = span / band.increment
    if band.mode is DiscountMode.STEP:
        steps = steps.quantize(Decimal("1"), rounding=ROUND_CEILING)
    return steps * band.rate


class DiscountBook:
    """Every schedule a house publishes, keyed by commodity and factor."""

    def __init__(self, schedules: Sequence[DiscountSchedule] = ()) -> None:
        self._by_key: dict[tuple[str, Factor], DiscountSchedule] = {}
        for schedule in schedules:
            self.add(schedule)

    def add(self, schedule: DiscountSchedule) -> None:
        key = (schedule.commodity_code, schedule.factor)
        if key in self._by_key:
            raise ScheduleError(f"two schedules for {key[0]}/{key[1].value}")
        self._by_key[key] = schedule

    def for_factor(self, commodity_code: str, factor: Factor) -> DiscountSchedule | None:
        return self._by_key.get((commodity_code, factor))

    def factors(self, commodity_code: str) -> tuple[Factor, ...]:
        found = [f for (code, f) in self._by_key if code == commodity_code]
        return tuple(sorted(found, key=lambda f: f.value))

    def total_rate(self, commodity_code: str, sample: Mapping[Factor, Decimal]) -> Decimal:
        """Every discount that applies to this sample, added up.

        Discounts add. There is no worst-factor rule here and that is the point:
        grade takes the poorest factor, money takes all of them.
        """
        total = Decimal("0")
        for factor, value in sample.items():
            schedule = self.for_factor(commodity_code, factor)
            if schedule is None:
                continue
            total += schedule.rate_for(value)
        return quantize(total, PRICE_PLACES)

    def breakdown(
        self, commodity_code: str, sample: Mapping[Factor, Decimal]
    ) -> dict[Factor, Decimal]:
        """The same thing, itemised, because a producer will ask."""
        out: dict[Factor, Decimal] = {}
        for factor, value in sample.items():
            schedule = self.for_factor(commodity_code, factor)
            if schedule is None:
                continue
            rate = schedule.rate_for(value)
            if rate != 0:
                out[factor] = rate
        return out
