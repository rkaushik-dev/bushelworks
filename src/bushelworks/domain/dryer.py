"""The dryer log, and what a point of moisture actually costs.

The house charges a published rate per point per bushel. What it pays is propane
and electricity, and the two have nothing to do with each other. Whether drying
made money in a wet year is a question the manager asks every November and
nobody could answer, because the propane bill is monthly and the drying charges
are per ticket.

This keeps a run log so the two can be put side by side:

    revenue = points times bushels times the published rate
    cost    = propane used times the propane price, plus fan hours times power

A run that dried nothing still costs money, because the burner was lit. Runs
with zero bushels are kept for that reason rather than dropped.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal

from ..errors import ValidationError
from ..money import amount, extend
from ..units import quantize

# Rules of thumb the house works to. Both are per bushel per point.
GALLONS_PER_POINT_PER_BUSHEL = Decimal("0.02")
KWH_PER_POINT_PER_BUSHEL = Decimal("0.012")


class DryerError(ValidationError):
    """Raised when a run cannot be logged or costed."""

    code = "dryer_error"


@dataclass(frozen=True, slots=True)
class Run:
    """One dryer run, from lighting the burner to shutting it down."""

    run_id: str
    location_code: str
    commodity_code: str
    started_at: datetime
    ended_at: datetime
    bushels: Decimal
    points_removed: Decimal
    propane_gallons: Decimal
    kwh: Decimal
    operator: str = ""

    def __post_init__(self) -> None:
        if not self.run_id:
            raise DryerError("a run needs an identifier")
        if self.ended_at < self.started_at:
            raise DryerError(f"{self.run_id}: ended before it started")
        for name in ("bushels", "points_removed", "propane_gallons", "kwh"):
            if getattr(self, name) < 0:
                raise DryerError(f"{self.run_id}: {name} is negative")

    @property
    def hours(self) -> Decimal:
        seconds = Decimal((self.ended_at - self.started_at).total_seconds())
        return quantize(seconds / Decimal("3600"), Decimal("0.01"))

    @property
    def point_bushels(self) -> Decimal:
        """Bushels times points, which is what everything is charged on."""
        return quantize(self.bushels * self.points_removed, Decimal("0.01"))

    @property
    def is_idle(self) -> bool:
        return self.bushels == 0

    def revenue(self, rate_per_point: Decimal) -> Decimal:
        return extend(self.point_bushels, rate_per_point)

    def cost(self, propane_price: Decimal, power_price: Decimal) -> Decimal:
        return amount(
            self.propane_gallons * propane_price + self.kwh * power_price
        )

    def margin(
        self, rate_per_point: Decimal, propane_price: Decimal, power_price: Decimal
    ) -> Decimal:
        return amount(
            self.revenue(rate_per_point) - self.cost(propane_price, power_price)
        )

    def gallons_per_point_bushel(self) -> Decimal | None:
        """How the run compares with the rule of thumb.

        Nothing on an idle run: dividing the burner's fuel by no bushels is a
        number with no meaning, and printing it as infinity helps nobody.
        """
        if self.point_bushels <= 0:
            return None
        return quantize(
            self.propane_gallons / self.point_bushels, Decimal("0.0001")
        )

    def is_thirsty(self) -> bool:
        """Whether the run burned more fuel than the rule of thumb allows."""
        rate = self.gallons_per_point_bushel()
        if rate is None:
            return False
        return rate > GALLONS_PER_POINT_PER_BUSHEL * Decimal("1.25")


@dataclass(frozen=True, slots=True)
class Season:
    """A run of runs, costed together."""

    opens: date
    closes: date
    runs: tuple[Run, ...]
    rate_per_point: Decimal
    propane_price: Decimal
    power_price: Decimal

    @property
    def bushels(self) -> Decimal:
        return quantize(
            sum((run.bushels for run in self.runs), Decimal("0")), Decimal("0.0001")
        )

    @property
    def point_bushels(self) -> Decimal:
        return quantize(
            sum((run.point_bushels for run in self.runs), Decimal("0")),
            Decimal("0.01"),
        )

    @property
    def revenue(self) -> Decimal:
        return amount(
            sum((run.revenue(self.rate_per_point) for run in self.runs), Decimal("0"))
        )

    @property
    def cost(self) -> Decimal:
        return amount(
            sum(
                (run.cost(self.propane_price, self.power_price) for run in self.runs),
                Decimal("0"),
            )
        )

    @property
    def margin(self) -> Decimal:
        return amount(self.revenue - self.cost)

    @property
    def hours(self) -> Decimal:
        return quantize(
            sum((run.hours for run in self.runs), Decimal("0")), Decimal("0.01")
        )

    @property
    def idle_runs(self) -> tuple[Run, ...]:
        return tuple(run for run in self.runs if run.is_idle)

    @property
    def thirsty_runs(self) -> tuple[Run, ...]:
        return tuple(run for run in self.runs if run.is_thirsty())

    def break_even_rate(self) -> Decimal | None:
        """What the house would have to charge to come out level."""
        if self.point_bushels <= 0:
            return None
        return quantize(self.cost / self.point_bushels, Decimal("0.0001"))


def season(
    runs: Sequence[Run],
    opens: date,
    closes: date,
    rate_per_point: Decimal,
    propane_price: Decimal,
    power_price: Decimal,
) -> Season:
    if closes < opens:
        raise DryerError("a season closes after it opens")
    inside = tuple(run for run in runs if opens <= run.started_at.date() <= closes)
    return Season(
        opens=opens,
        closes=closes,
        runs=inside,
        rate_per_point=rate_per_point,
        propane_price=propane_price,
        power_price=power_price,
    )
