"""The queue on the driveway at harvest.

Four trucks deep is a normal Tuesday and forty is the third week of October. The
order they are worked is not first come first served, and pretending it is makes
the queue longer rather than fairer:

* a truck already on the scale finishes first, always
* a probe that is already drawn is worked before one that is not, because the
  sample is sitting on the bench going warm
* a company truck coming back for another load beats a producer's truck, because
  it is on a round trip the house is paying for by the hour
* otherwise the oldest arrival wins

The last rule is the one everybody thinks is the whole rule.
"""

from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import datetime, timedelta
from enum import Enum

from ..errors import ConflictError, ValidationError


class Stage(str, Enum):
    """Where a truck is in the process."""

    ARRIVED = "arrived"
    PROBED = "probed"
    ON_SCALE = "on_scale"
    DUMPING = "dumping"
    DONE = "done"


class Priority(int, Enum):
    ON_SCALE = 0
    PROBED = 1
    COMPANY = 2
    WAITING = 3


class DrivewayError(ValidationError):
    """Raised when a truck cannot move where it is asked to."""

    code = "driveway_error"


# What a truck may do next. Nothing skips a stage and nothing goes backwards;
# a truck that has to be reworked leaves the queue and rejoins it.
NEXT: dict[Stage, tuple[Stage, ...]] = {
    Stage.ARRIVED: (Stage.PROBED,),
    Stage.PROBED: (Stage.ON_SCALE,),
    Stage.ON_SCALE: (Stage.DUMPING,),
    Stage.DUMPING: (Stage.DONE,),
    Stage.DONE: (),
}


@dataclass(frozen=True, slots=True)
class Truck:
    """One truck in the queue."""

    truck_id: str
    party_code: str
    commodity_code: str
    arrived_at: datetime
    stage: Stage = Stage.ARRIVED
    is_company: bool = False
    probe_id: str | None = None

    def __post_init__(self) -> None:
        if not self.truck_id:
            raise DrivewayError("a truck needs an id")
        if self.stage is Stage.PROBED and not self.probe_id:
            raise DrivewayError(f"{self.truck_id}: probed but no sample id")

    @property
    def priority(self) -> Priority:
        if self.stage in (Stage.ON_SCALE, Stage.DUMPING):
            return Priority.ON_SCALE
        if self.stage is Stage.PROBED:
            return Priority.PROBED
        if self.is_company:
            return Priority.COMPANY
        return Priority.WAITING

    @property
    def sort_key(self) -> tuple[int, datetime, str]:
        return (self.priority.value, self.arrived_at, self.truck_id)

    def waiting_for(self, now: datetime) -> timedelta:
        if now < self.arrived_at:
            raise DrivewayError(f"{self.truck_id}: {now} is before it arrived")
        return now - self.arrived_at

    def advance(self, to: Stage, probe_id: str | None = None) -> Truck:
        if to not in NEXT[self.stage]:
            raise ConflictError(
                f"{self.truck_id} is {self.stage.value} and cannot go to {to.value}"
            )
        if to is Stage.PROBED and not probe_id:
            raise DrivewayError(f"{self.truck_id}: a probe needs a sample id")
        return replace(self, stage=to, probe_id=probe_id or self.probe_id)


class Driveway:
    """The queue at one site."""

    def __init__(self, location_code: str) -> None:
        self.location_code = location_code
        self._trucks: dict[str, Truck] = {}

    def arrive(self, truck: Truck) -> None:
        if truck.truck_id in self._trucks:
            raise ConflictError(f"{truck.truck_id} is already on the driveway")
        self._trucks[truck.truck_id] = truck

    def get(self, truck_id: str) -> Truck:
        try:
            return self._trucks[truck_id]
        except KeyError as exc:
            raise DrivewayError(f"{truck_id} is not on the driveway") from exc

    def advance(
        self, truck_id: str, to: Stage, probe_id: str | None = None
    ) -> Truck:
        moved = self.get(truck_id).advance(to, probe_id)
        if to is Stage.ON_SCALE:
            occupant = self.on_scale()
            if occupant is not None:
                raise ConflictError(f"{occupant.truck_id} is already on the scale")
        self._trucks[truck_id] = moved
        if moved.stage is Stage.DONE:
            del self._trucks[truck_id]
        return moved

    def leave(self, truck_id: str) -> Truck:
        """Pull a truck out of the queue without finishing it."""
        truck = self.get(truck_id)
        del self._trucks[truck_id]
        return truck

    def on_scale(self) -> Truck | None:
        for truck in self._trucks.values():
            if truck.stage in (Stage.ON_SCALE, Stage.DUMPING):
                return truck
        return None

    def queue(self) -> tuple[Truck, ...]:
        """Everybody waiting, in the order they will be worked."""
        return tuple(sorted(self._trucks.values(), key=lambda t: t.sort_key))

    def next_up(self) -> Truck | None:
        ordered = self.queue()
        return ordered[0] if ordered else None

    def depth(self) -> int:
        return len(self._trucks)

    def waiting_longest(self, now: datetime) -> Truck | None:
        if not self._trucks:
            return None
        return min(self._trucks.values(), key=lambda t: (t.arrived_at, t.truck_id))

    def over_wait(self, now: datetime, limit: timedelta) -> tuple[Truck, ...]:
        """Trucks that have been on the driveway longer than the house allows.

        The manager watches this number. A queue with three trucks over forty
        minutes is a queue that is about to become forty trucks.
        """
        return tuple(
            truck
            for truck in self.queue()
            if truck.waiting_for(now) > limit
        )

    def by_commodity(self) -> dict[str, int]:
        counts: dict[str, int] = {}
        for truck in self._trucks.values():
            counts[truck.commodity_code] = counts.get(truck.commodity_code, 0) + 1
        return dict(sorted(counts.items()))
