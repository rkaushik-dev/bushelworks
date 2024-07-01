"""The daily position: what the house owns and what it owes.

Grain in the bin is not the same as grain the house owns. A country elevator
holding a million bushels may be long two hundred thousand and short the rest,
and the difference is the only number the hedge desk cares about.

The identity the position report has to satisfy every night:

    owned = on_hand - storage_obligation - deferred_price
    net_long = owned + purchased_forward - sold_forward

Storage bushels belong to the producer and carry no price risk for the house.
Deferred price bushels have been delivered and title has passed, but the price
has not been agreed, so the house is long them and must hedge them. That
distinction is the one that gets missed, and missing it puts a house short into
a rally with no idea it is there.
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal

from ..errors import ValidationError
from ..units import BUSHEL_PLACES, quantize


class PositionError(ValidationError):
    """Raised when a position cannot be assembled or does not reconcile."""

    code = "position_error"


@dataclass(frozen=True, slots=True)
class PositionLine:
    """One commodity at one location on one day."""

    as_of: date
    commodity_code: str
    location_code: str
    on_hand: Decimal = Decimal("0")
    storage_obligation: Decimal = Decimal("0")
    deferred_price: Decimal = Decimal("0")
    purchased_forward: Decimal = Decimal("0")
    sold_forward: Decimal = Decimal("0")
    in_transit: Decimal = Decimal("0")

    def __post_init__(self) -> None:
        for name in (
            "on_hand",
            "storage_obligation",
            "deferred_price",
            "purchased_forward",
            "sold_forward",
            "in_transit",
        ):
            if getattr(self, name) < 0:
                raise PositionError(
                    f"{self.commodity_code}/{self.location_code}: {name} is negative"
                )

    @property
    def owned(self) -> Decimal:
        """Bushels in the bin that belong to the house."""
        return quantize(
            self.on_hand - self.storage_obligation - self.deferred_price,
            BUSHEL_PLACES,
        )

    @property
    def net_long(self) -> Decimal:
        """The number the hedge desk works from.

        Deferred price bushels are added back because the house carries their
        price risk even though it has not paid for them yet.
        """
        return quantize(
            self.owned
            + self.deferred_price
            + self.purchased_forward
            - self.sold_forward
            + self.in_transit,
            BUSHEL_PLACES,
        )

    @property
    def is_short(self) -> bool:
        return self.net_long < 0

    @property
    def obligation(self) -> Decimal:
        """Everything the house owes somebody in bushels."""
        return quantize(
            self.storage_obligation + self.deferred_price + self.sold_forward,
            BUSHEL_PLACES,
        )

    def combine(self, other: PositionLine) -> PositionLine:
        """Add two lines for the same commodity on the same day."""
        if self.commodity_code != other.commodity_code:
            raise PositionError("cannot combine positions in different commodities")
        if self.as_of != other.as_of:
            raise PositionError("cannot combine positions from different days")
        location = self.location_code if self.location_code == other.location_code else "*"
        return PositionLine(
            as_of=self.as_of,
            commodity_code=self.commodity_code,
            location_code=location,
            on_hand=self.on_hand + other.on_hand,
            storage_obligation=self.storage_obligation + other.storage_obligation,
            deferred_price=self.deferred_price + other.deferred_price,
            purchased_forward=self.purchased_forward + other.purchased_forward,
            sold_forward=self.sold_forward + other.sold_forward,
            in_transit=self.in_transit + other.in_transit,
        )


@dataclass
class PositionReport:
    """Every commodity and location for one day."""

    as_of: date
    lines: list[PositionLine] = field(default_factory=list)

    def add(self, line: PositionLine) -> None:
        if line.as_of != self.as_of:
            raise PositionError(
                f"line dated {line.as_of} does not belong on the {self.as_of} report"
            )
        self.lines.append(line)

    def by_commodity(self, commodity_code: str) -> PositionLine:
        """One rolled up line for a commodity across every location."""
        matching = [line for line in self.lines if line.commodity_code == commodity_code]
        if not matching:
            raise PositionError(f"no {commodity_code} position on {self.as_of}")
        rolled = matching[0]
        for line in matching[1:]:
            rolled = rolled.combine(line)
        return rolled

    def commodities(self) -> tuple[str, ...]:
        return tuple(sorted({line.commodity_code for line in self.lines}))

    def total_owned(self) -> Decimal:
        return quantize(
            sum((line.owned for line in self.lines), Decimal("0")), BUSHEL_PLACES
        )

    def shorts(self) -> tuple[PositionLine, ...]:
        return tuple(line for line in self.lines if line.is_short)


def hedge_requirement(line: PositionLine, contract_size: Decimal = Decimal("5000")) -> int:
    """How many futures contracts would flatten this position.

    Rounded toward zero on purpose. A house that rounds its hedge up is trading,
    and the board does not let it call that hedging.
    """
    if contract_size <= 0:
        raise PositionError("a futures contract cannot be zero bushels")
    lots = line.net_long / contract_size
    return int(lots.to_integral_value(rounding="ROUND_DOWN"))


def reconcile(line: PositionLine, counted: Decimal, tolerance: Decimal) -> Decimal:
    """Compare a measured bin count against the book and refuse a wild gap.

    Bin measurement is good to about half a percent on a good day. A variance
    inside tolerance is written off to shrink and dust; one outside it means
    somebody has a ticket in a drawer.
    """
    if tolerance < 0:
        raise PositionError("tolerance cannot be negative")
    variance = quantize(Decimal(counted) - line.on_hand, BUSHEL_PLACES)
    allowed = quantize(line.on_hand * tolerance / Decimal("100"), BUSHEL_PLACES)
    if abs(variance) > allowed:
        raise PositionError(
            f"{line.commodity_code}/{line.location_code}: measured variance "
            f"{variance} exceeds the {tolerance}% tolerance of {allowed}"
        )
    return variance


def roll_forward(lines: Iterable[PositionLine], to_day: date) -> list[PositionLine]:
    """Carry yesterday's lines onto today before the day's activity is applied."""
    from dataclasses import replace

    return [replace(line, as_of=to_day) for line in lines]
