"""The warehouse licence return.

Monthly, to the state. It says how much grain the house is holding for other
people, how much it owns, and whether the two together fit inside the licensed
capacity. The last one is the question with teeth: a licensed warehouse over
capacity is a licence problem, not a logistics one.

The obligation figure is the one that matters and it is not what is in the bins.
It is storage plus deferred price plus warehouse receipts outstanding, and a
house that reports bin contents instead has reported the wrong number in the
producer's favour and its own disfavour.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from datetime import date
from decimal import Decimal

from ..errors import ValidationError
from ..units import BUSHEL_PLACES, quantize

WIDTH = 72

# The state wants a note when a house is inside this much of its licence.
TIGHT_MARGIN = Decimal("0.05")


class LicenceError(ValidationError):
    """Raised when a return cannot be assembled."""

    code = "licence_error"


@dataclass(frozen=True, slots=True)
class Return:
    """One site, one month."""

    location_code: str
    licence_number: str
    as_of: date
    licensed_capacity: Decimal
    storage_obligation: Decimal
    deferred_price: Decimal
    receipts_outstanding: Decimal
    house_owned: Decimal

    def __post_init__(self) -> None:
        if self.licensed_capacity <= 0:
            raise LicenceError(f"{self.location_code}: no licensed capacity")
        for name in (
            "storage_obligation",
            "deferred_price",
            "receipts_outstanding",
            "house_owned",
        ):
            if getattr(self, name) < 0:
                raise LicenceError(f"{self.location_code}: {name} is negative")

    @property
    def obligation(self) -> Decimal:
        """Everything the house owes somebody in grain."""
        return quantize(
            self.storage_obligation + self.deferred_price + self.receipts_outstanding,
            BUSHEL_PLACES,
        )

    @property
    def total_held(self) -> Decimal:
        return quantize(self.obligation + self.house_owned, BUSHEL_PLACES)

    @property
    def unused_capacity(self) -> Decimal:
        left = self.licensed_capacity - self.total_held
        return quantize(left, BUSHEL_PLACES)

    @property
    def is_over_capacity(self) -> bool:
        return self.total_held > self.licensed_capacity

    @property
    def is_tight(self) -> bool:
        """Inside the margin the state wants a note about."""
        if self.is_over_capacity:
            return True
        margin = self.licensed_capacity * TIGHT_MARGIN
        return self.unused_capacity <= margin

    @property
    def percent_used(self) -> Decimal:
        return quantize(
            self.total_held / self.licensed_capacity * Decimal("100"),
            Decimal("0.1"),
        )


def render(one: Return, house_name: str = "BUSHELWORKS ELEVATOR") -> str:
    """The return as it goes in the envelope."""
    lines = [
        house_name.center(WIDTH).rstrip(),
        f"WAREHOUSE RETURN {one.as_of.isoformat()}".center(WIDTH).rstrip(),
        "=" * WIDTH,
        f"Site          {one.location_code}",
        f"Licence       {one.licence_number}",
        "-" * WIDTH,
        f"{'storage obligation':<32}{one.storage_obligation:>20}",
        f"{'deferred price':<32}{one.deferred_price:>20}",
        f"{'receipts outstanding':<32}{one.receipts_outstanding:>20}",
        f"{'total obligation':<32}{one.obligation:>20}",
        "-" * WIDTH,
        f"{'house owned':<32}{one.house_owned:>20}",
        f"{'total held':<32}{one.total_held:>20}",
        f"{'licensed capacity':<32}{one.licensed_capacity:>20}",
        f"{'unused':<32}{one.unused_capacity:>20}",
        f"{'used':<32}{one.percent_used:>19}%",
    ]
    if one.is_over_capacity:
        lines.append("")
        lines.append("OVER LICENSED CAPACITY. This has to be explained.")
    elif one.is_tight:
        lines.append("")
        lines.append("Within five percent of the licence.")
    return "\n".join(lines)


def combine(returns: Sequence[Return], as_of: date) -> Return:
    """Roll several sites into one house wide return."""
    if not returns:
        raise LicenceError("nothing to combine")
    if any(one.as_of != as_of for one in returns):
        raise LicenceError("cannot combine returns from different months")
    return Return(
        location_code="*",
        licence_number=", ".join(sorted(one.licence_number for one in returns)),
        as_of=as_of,
        licensed_capacity=sum(
            (one.licensed_capacity for one in returns), Decimal("0")
        ),
        storage_obligation=sum(
            (one.storage_obligation for one in returns), Decimal("0")
        ),
        deferred_price=sum((one.deferred_price for one in returns), Decimal("0")),
        receipts_outstanding=sum(
            (one.receipts_outstanding for one in returns), Decimal("0")
        ),
        house_owned=sum((one.house_owned for one in returns), Decimal("0")),
    )


def over_capacity(returns: Sequence[Return]) -> list[Return]:
    return [one for one in returns if one.is_over_capacity]
