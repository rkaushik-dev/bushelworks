"""Where the bushels went.

The house takes shrink off every wet load and then loses some more through the
dryer and the leg. Those two numbers are never the same and the difference is
either a profit or an invisible loss, depending which way it runs.

    kept = shrink taken off the producers
    lost = what the scale says actually went missing
    gain = kept less lost

A positive gain means the shrink table is generous to the house and the state
will eventually ask about it. A negative gain means the house is paying for
grain it does not have, which is worse and much quieter.

Nothing here recomputes shrink. It compares what was charged with what was
measured, and both come in from outside.
"""

from __future__ import annotations

import csv
import io
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import date
from decimal import Decimal

from ..errors import ValidationError
from ..units import BUSHEL_PLACES, quantize

WIDTH = 80

# A gain outside this fraction of the bushels handled is worth explaining.
NOTABLE = Decimal("0.002")


class ShrinkageError(ValidationError):
    """Raised when a reconciliation cannot be made."""

    code = "shrinkage_error"


@dataclass(frozen=True, slots=True)
class Period:
    """One commodity at one site over a span."""

    commodity_code: str
    location_code: str
    opens: date
    closes: date
    wet_bushels_in: Decimal
    shrink_charged: Decimal
    measured_loss: Decimal
    dried_bushels: Decimal = Decimal("0")

    def __post_init__(self) -> None:
        if self.closes < self.opens:
            raise ShrinkageError(
                f"{self.commodity_code}: the period closes before it opens"
            )
        if self.wet_bushels_in < 0 or self.shrink_charged < 0:
            raise ShrinkageError(f"{self.commodity_code}: negative bushels")

    @property
    def gain(self) -> Decimal:
        """Charged less measured. Positive is in the house's favour."""
        return quantize(self.shrink_charged - self.measured_loss, BUSHEL_PLACES)

    @property
    def charged_percent(self) -> Decimal:
        if self.wet_bushels_in <= 0:
            return Decimal("0.0000")
        return quantize(
            self.shrink_charged / self.wet_bushels_in * Decimal("100"),
            Decimal("0.0001"),
        )

    @property
    def measured_percent(self) -> Decimal:
        if self.wet_bushels_in <= 0:
            return Decimal("0.0000")
        return quantize(
            self.measured_loss / self.wet_bushels_in * Decimal("100"),
            Decimal("0.0001"),
        )

    @property
    def is_notable(self) -> bool:
        if self.wet_bushels_in <= 0:
            return False
        return abs(self.gain) > self.wet_bushels_in * NOTABLE

    @property
    def direction(self) -> str:
        if self.gain > 0:
            return "in the house's favour"
        if self.gain < 0:
            return "against the house"
        return "flat"

    @property
    def percent_dried(self) -> Decimal:
        if self.wet_bushels_in <= 0:
            return Decimal("0.0")
        return quantize(
            self.dried_bushels / self.wet_bushels_in * Decimal("100"), Decimal("0.1")
        )


def render(periods: Sequence[Period], house_name: str = "BUSHELWORKS ELEVATOR") -> str:
    """The sheet the grain accountant takes to the manager."""
    lines = [
        house_name.center(WIDTH).rstrip(),
        "SHRINK RECONCILIATION".center(WIDTH).rstrip(),
        "=" * WIDTH,
        f"{'CROP':<7}{'SITE':<8}{'WET IN':>14}{'CHARGED':>12}{'MEASURED':>12}"
        f"{'GAIN':>12}{'CHG %':>8}{'MEA %':>7}",
        "-" * WIDTH,
    ]
    for period in sorted(
        periods, key=lambda item: (item.commodity_code, item.location_code)
    ):
        lines.append(
            f"{period.commodity_code:<7}{period.location_code:<8}"
            f"{period.wet_bushels_in.quantize(Decimal('1')):>14}"
            f"{period.shrink_charged.quantize(Decimal('1')):>12}"
            f"{period.measured_loss.quantize(Decimal('1')):>12}"
            f"{period.gain.quantize(Decimal('1')):>12}"
            f"{period.charged_percent.quantize(Decimal('0.01')):>8}"
            f"{period.measured_percent.quantize(Decimal('0.01')):>7}"
        )
    lines.append("-" * WIDTH)
    notable = [period for period in periods if period.is_notable]
    lines.append(f"{len(periods)} lines, {len(notable)} worth explaining")
    for period in notable:
        lines.append(
            f"  {period.commodity_code} at {period.location_code}: "
            f"{abs(period.gain).quantize(Decimal('1'))} bushels {period.direction}"
        )
    return "\n".join(lines)


def to_csv(periods: Sequence[Period]) -> str:
    buffer = io.StringIO()
    writer = csv.writer(buffer, lineterminator="\n")
    writer.writerow(
        [
            "commodity_code",
            "location_code",
            "opens",
            "closes",
            "wet_bushels_in",
            "dried_bushels",
            "shrink_charged",
            "measured_loss",
            "gain",
            "charged_percent",
            "measured_percent",
            "is_notable",
        ]
    )
    for period in sorted(periods, key=lambda item: item.commodity_code):
        writer.writerow(
            [
                period.commodity_code,
                period.location_code,
                period.opens.isoformat(),
                period.closes.isoformat(),
                period.wet_bushels_in,
                period.dried_bushels,
                period.shrink_charged,
                period.measured_loss,
                period.gain,
                period.charged_percent,
                period.measured_percent,
                "yes" if period.is_notable else "no",
            ]
        )
    return buffer.getvalue()


def total_gain(periods: Sequence[Period]) -> Decimal:
    return quantize(sum((period.gain for period in periods), Decimal("0")), BUSHEL_PLACES)


def against_the_house(periods: Sequence[Period]) -> list[Period]:
    """The quiet ones. A house losing shrink does not notice for a year."""
    return [period for period in periods if period.gain < 0]
