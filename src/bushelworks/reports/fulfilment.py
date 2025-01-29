"""How the contract book is filling.

The desk asks this twice a week from October to Christmas. It is not a list of
contracts; it is a list of the ones that will not fill, because those are the
ones that turn into a phone call while there is still time to do something.

Three states matter and the middle one is the useful one:

* **filled** is inside tolerance and needs nothing
* **at risk** is open with more bushels outstanding than the producer has been
  hauling per week times the weeks left
* **short** is past its window with bushels still outstanding, which is a
  default and a conversation with somebody senior
"""

from __future__ import annotations

import csv
import io
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import date
from decimal import Decimal
from enum import Enum

from ..domain.contracts import PurchaseContract
from ..errors import ValidationError
from ..units import BUSHEL_PLACES, quantize

WIDTH = 90


class State(str, Enum):
    FILLED = "filled"
    ON_TRACK = "on_track"
    AT_RISK = "at_risk"
    SHORT = "short"


class FulfilmentError(ValidationError):
    """Raised when a contract cannot be assessed."""

    code = "fulfilment_error"


@dataclass(frozen=True, slots=True)
class Line:
    """One contract, assessed against the calendar."""

    number: str
    party_code: str
    commodity_code: str
    contracted: Decimal
    applied: Decimal
    closes: date
    as_of: date
    weekly_rate: Decimal = Decimal("0")
    tolerance_percent: Decimal = Decimal("2")

    @property
    def outstanding(self) -> Decimal:
        floor = self.contracted * (
            Decimal("100") - self.tolerance_percent
        ) / Decimal("100")
        left = floor - self.applied
        return quantize(left, BUSHEL_PLACES) if left > 0 else Decimal("0.0000")

    @property
    def weeks_left(self) -> Decimal:
        days = (self.closes - self.as_of).days
        return Decimal(days) / Decimal("7") if days > 0 else Decimal("0")

    @property
    def deliverable(self) -> Decimal:
        """What the producer can be expected to haul before the window shuts."""
        return quantize(self.weekly_rate * self.weeks_left, BUSHEL_PLACES)

    @property
    def state(self) -> State:
        if self.outstanding <= 0:
            return State.FILLED
        if self.as_of > self.closes:
            return State.SHORT
        if self.weekly_rate <= 0:
            return State.AT_RISK
        return State.ON_TRACK if self.deliverable >= self.outstanding else State.AT_RISK

    @property
    def needs_a_call(self) -> bool:
        return self.state in (State.AT_RISK, State.SHORT)


def line_for(
    contract: PurchaseContract,
    applied: Decimal,
    as_of: date,
    weekly_rate: Decimal = Decimal("0"),
) -> Line:
    """Assess one contract against what has landed on it."""
    if applied < 0:
        raise FulfilmentError(f"{contract.number}: applied cannot be negative")
    return Line(
        number=contract.number,
        party_code=contract.party_id,
        commodity_code=contract.commodity_code,
        contracted=contract.bushels,
        applied=Decimal(applied),
        closes=contract.window.closes,
        as_of=as_of,
        weekly_rate=Decimal(weekly_rate),
        tolerance_percent=contract.tolerance_percent,
    )


def render(lines: Sequence[Line], as_of: date) -> str:
    """The sheet the desk works down."""
    out = [
        f"CONTRACT FULFILMENT {as_of.isoformat()}".center(WIDTH).rstrip(),
        "=" * WIDTH,
        f"{'CONTRACT':<16}{'PRODUCER':<16}{'CROP':<6}{'CONTRACTED':>14}"
        f"{'APPLIED':>14}{'OUTSTANDING':>14}{'STATE':>10}",
        "-" * WIDTH,
    ]
    for line in sorted(lines, key=lambda item: (item.state.value, item.number)):
        out.append(
            f"{line.number:<16}{line.party_code[:14]:<16}{line.commodity_code:<6}"
            f"{line.contracted.quantize(Decimal('1')):>14}"
            f"{line.applied.quantize(Decimal('1')):>14}"
            f"{line.outstanding.quantize(Decimal('1')):>14}"
            f"{line.state.value:>10}"
        )
    out.append("-" * WIDTH)
    calls = [line for line in lines if line.needs_a_call]
    out.append(f"{len(lines)} contracts, {len(calls)} need a call")
    if calls:
        out.append("")
        out.append("RING TODAY: " + ", ".join(sorted(line.number for line in calls)))
    return "\n".join(out)


def to_csv(lines: Sequence[Line]) -> str:
    buffer = io.StringIO()
    writer = csv.writer(buffer, lineterminator="\n")
    writer.writerow(
        [
            "number",
            "party_code",
            "commodity_code",
            "contracted",
            "applied",
            "outstanding",
            "closes",
            "weeks_left",
            "deliverable",
            "state",
        ]
    )
    for line in sorted(lines, key=lambda item: item.number):
        writer.writerow(
            [
                line.number,
                line.party_code,
                line.commodity_code,
                line.contracted,
                line.applied,
                line.outstanding,
                line.closes.isoformat(),
                line.weeks_left.quantize(Decimal("0.1")),
                line.deliverable,
                line.state.value,
            ]
        )
    return buffer.getvalue()


def at_risk(lines: Sequence[Line]) -> list[Line]:
    return [line for line in lines if line.state is State.AT_RISK]


def short(lines: Sequence[Line]) -> list[Line]:
    return [line for line in lines if line.state is State.SHORT]


def outstanding_total(lines: Sequence[Line]) -> Decimal:
    return quantize(
        sum((line.outstanding for line in lines), Decimal("0")), BUSHEL_PLACES
    )
