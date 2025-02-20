"""Trucks and drivers, and the tare weights that go with them.

A tare is not a property of a truck. It is a property of a truck on a day: an
empty hopper weighs different amounts wet and dry, with and without a tarp, with
and without the driver in the cab. Houses that keep one tare per truck and use it
for a season are the ones whose inventory drifts.

So a stored tare has a date on it and an age, and the scale house is told when it
is stale. A stale tare is not refused, because at four in the morning refusing it
means the driver waits; it is flagged, and the flag is on the ticket.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass, field, replace
from datetime import date, timedelta
from decimal import Decimal

from ..errors import ConflictError, ValidationError

# How long a stored tare is trusted. Past this the scale house is asked to
# weigh the truck empty again.
TARE_LIFE = timedelta(days=30)

# A hopper bottom empty. Anything far outside this is a keying mistake.
PLAUSIBLE_TARE = (Decimal("18000"), Decimal("36000"))


class FleetError(ValidationError):
    """Raised when a truck or a tare cannot stand as recorded."""

    code = "fleet_error"


@dataclass(frozen=True, slots=True)
class Tare:
    """One empty weighing."""

    pounds: Decimal
    weighed_on: date
    ticket_number: str | None = None

    def __post_init__(self) -> None:
        low, high = PLAUSIBLE_TARE
        if not (low <= self.pounds <= high):
            raise FleetError(
                f"a tare of {self.pounds} lb is outside {low} to {high}"
            )

    def expires_on(self) -> date:
        return self.weighed_on + TARE_LIFE

    def is_current_on(self, day: date) -> bool:
        return self.weighed_on <= day <= self.expires_on()

    def age_days(self, on: date) -> int:
        return (on - self.weighed_on).days


@dataclass(frozen=True, slots=True)
class Truck:
    """One truck the house weighs."""

    truck_id: str
    party_code: str
    description: str = ""
    is_company: bool = False
    tares: tuple[Tare, ...] = field(default_factory=tuple)
    retired_on: date | None = None

    def __post_init__(self) -> None:
        if not self.truck_id:
            raise FleetError("a truck needs an identifier")
        if not self.party_code:
            raise FleetError(f"{self.truck_id}: a truck belongs to somebody")

    @property
    def is_retired(self) -> bool:
        return self.retired_on is not None

    def latest_tare(self) -> Tare | None:
        if not self.tares:
            return None
        return max(self.tares, key=lambda tare: tare.weighed_on)

    def tare_on(self, day: date) -> Tare | None:
        """The most recent tare that is still current on a day."""
        usable = [tare for tare in self.tares if tare.is_current_on(day)]
        if not usable:
            return None
        return max(usable, key=lambda tare: tare.weighed_on)

    def tare_is_stale(self, day: date) -> bool:
        """Whether the scale house should weigh this truck empty again."""
        return self.tare_on(day) is None

    def weigh_empty(self, tare: Tare) -> Truck:
        if self.is_retired:
            raise ConflictError(f"{self.truck_id} was retired on {self.retired_on}")
        if any(item.weighed_on == tare.weighed_on for item in self.tares):
            raise ConflictError(
                f"{self.truck_id} was already weighed empty on {tare.weighed_on}"
            )
        return replace(self, tares=(*self.tares, tare))

    def retire(self, on: date) -> Truck:
        if self.is_retired:
            raise ConflictError(f"{self.truck_id} is already retired")
        return replace(self, retired_on=on)

    def drift(self) -> Decimal | None:
        """How far the tare has moved since the first weighing.

        Twelve hundred pounds over a season is a tarp and a toolbox. Four
        thousand is a different truck wearing the same number, which happens.
        """
        if len(self.tares) < 2:
            return None
        ordered = sorted(self.tares, key=lambda tare: tare.weighed_on)
        return ordered[-1].pounds - ordered[0].pounds


class Fleet:
    """Every truck the house knows about."""

    def __init__(self) -> None:
        self._trucks: dict[str, Truck] = {}

    def add(self, truck: Truck) -> None:
        if truck.truck_id in self._trucks:
            raise ConflictError(f"{truck.truck_id} is already on the fleet")
        self._trucks[truck.truck_id] = truck

    def get(self, truck_id: str) -> Truck:
        try:
            return self._trucks[truck_id]
        except KeyError as exc:
            raise FleetError(f"{truck_id} is not on the fleet") from exc

    def replace(self, truck: Truck) -> None:
        self.get(truck.truck_id)
        self._trucks[truck.truck_id] = truck

    def active(self) -> tuple[Truck, ...]:
        return tuple(
            sorted(
                (truck for truck in self._trucks.values() if not truck.is_retired),
                key=lambda truck: truck.truck_id,
            )
        )

    def for_party(self, party_code: str) -> tuple[Truck, ...]:
        return tuple(
            truck for truck in self.active() if truck.party_code == party_code
        )

    def needing_a_tare(self, day: date) -> tuple[Truck, ...]:
        """The list the scale house works down first thing in the morning."""
        return tuple(truck for truck in self.active() if truck.tare_is_stale(day))

    def drifting(self, threshold: Decimal = Decimal("2000")) -> tuple[Truck, ...]:
        found = []
        for truck in self.active():
            drift = truck.drift()
            if drift is not None and abs(drift) >= threshold:
                found.append(truck)
        return tuple(found)


def average_tare(tares: Sequence[Tare]) -> Decimal | None:
    if not tares:
        return None
    total = sum((tare.pounds for tare in tares), Decimal("0"))
    return (total / Decimal(len(tares))).quantize(Decimal("1"))
