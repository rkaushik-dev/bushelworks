"""Bins, and what is physically in them.

The position report says what the house owns. This says where it is, which is a
different question and the one that matters when a buyer wants a hundred
thousand bushels of No. 2 loaded by Friday.

A bin holds one commodity at a time and its contents are a running blend. Every
load in changes the average of what is in there, and that average is what a
draw out of the bin will test. This is the same weighted blend the blending desk
does, except that nobody chose it.

Capacity is in bushels at a nominal test weight, which is how the licence states
it, and a bin filled with light corn will hit its capacity in bushels before it
hits it in feet. The house measures level and converts; this holds the number
the licence cares about.
"""

from __future__ import annotations

from dataclasses import dataclass, field, replace
from decimal import Decimal
from typing import Mapping

from ..errors import ConflictError, ValidationError
from ..units import BUSHEL_PLACES, quantize
from .commodity import Factor

# A bin is called full at this fraction of capacity. Above it the house stops
# putting grain in, because the last four feet are where the spoilage is.
WORKING_FILL = Decimal("0.97")


class BinError(ValidationError):
    """Raised when a bin cannot take or give what is asked."""

    code = "bin_error"


@dataclass(frozen=True, slots=True)
class Bin:
    """One bin and what is in it."""

    bin_id: str
    location_code: str
    capacity_bushels: Decimal
    commodity_code: str | None = None
    bushels: Decimal = Decimal("0")
    sample: Mapping[Factor, Decimal] = field(default_factory=dict)
    is_aerated: bool = False
    out_of_service: bool = False

    def __post_init__(self) -> None:
        if self.capacity_bushels <= 0:
            raise BinError(f"{self.bin_id}: a bin needs a capacity")
        if self.bushels < 0:
            raise BinError(f"{self.bin_id}: contents cannot be negative")
        if self.bushels > 0 and self.commodity_code is None:
            raise BinError(f"{self.bin_id}: grain in a bin has to be something")
        if self.bushels > self.capacity_bushels:
            raise BinError(
                f"{self.bin_id}: {self.bushels} bushels will not fit in "
                f"{self.capacity_bushels}"
            )

    @property
    def is_empty(self) -> bool:
        return self.bushels == 0

    @property
    def working_capacity(self) -> Decimal:
        return quantize(self.capacity_bushels * WORKING_FILL, BUSHEL_PLACES)

    @property
    def headroom(self) -> Decimal:
        """What can still go in before the bin is called full."""
        room = self.working_capacity - self.bushels
        return room if room > 0 else Decimal("0.0000")

    @property
    def percent_full(self) -> Decimal:
        return quantize(
            self.bushels / self.capacity_bushels * Decimal("100"), Decimal("0.1")
        )

    @property
    def is_full(self) -> bool:
        return self.headroom == 0

    def accepts(self, commodity_code: str, bushels: Decimal) -> bool:
        if self.out_of_service:
            return False
        if self.commodity_code not in (None, commodity_code):
            return False
        return Decimal(bushels) <= self.headroom

    def fill(
        self,
        commodity_code: str,
        bushels: Decimal,
        sample: Mapping[Factor, Decimal],
    ) -> "Bin":
        """Put a load in, blending it with what is already there."""
        wanted = quantize(Decimal(bushels), BUSHEL_PLACES)
        if wanted <= 0:
            raise BinError(f"{self.bin_id}: nothing to put in")
        if self.out_of_service:
            raise ConflictError(f"{self.bin_id} is out of service")
        if self.commodity_code not in (None, commodity_code):
            raise ConflictError(
                f"{self.bin_id} holds {self.commodity_code}, not {commodity_code}"
            )
        if wanted > self.headroom:
            raise BinError(
                f"{self.bin_id}: only {self.headroom} bushels of room, asked for {wanted}"
            )
        return replace(
            self,
            commodity_code=commodity_code,
            bushels=quantize(self.bushels + wanted, BUSHEL_PLACES),
            sample=_blend(self.sample, self.bushels, sample, wanted),
        )

    def draw(self, bushels: Decimal) -> "Bin":
        """Take grain out. What is left tests the same as what came out."""
        wanted = quantize(Decimal(bushels), BUSHEL_PLACES)
        if wanted <= 0:
            raise BinError(f"{self.bin_id}: nothing to draw")
        if wanted > self.bushels:
            raise BinError(
                f"{self.bin_id}: only {self.bushels} bushels in it, asked for {wanted}"
            )
        left = quantize(self.bushels - wanted, BUSHEL_PLACES)
        if left == 0:
            return replace(self, bushels=Decimal("0"), commodity_code=None, sample={})
        return replace(self, bushels=left)

    def take_out_of_service(self, reason: str) -> "Bin":
        if not reason:
            raise BinError(f"{self.bin_id}: needs a reason")
        if self.out_of_service:
            raise ConflictError(f"{self.bin_id} is already out of service")
        return replace(self, out_of_service=True)

    def return_to_service(self) -> "Bin":
        if not self.out_of_service:
            raise ConflictError(f"{self.bin_id} is already in service")
        return replace(self, out_of_service=False)


def _blend(
    existing: Mapping[Factor, Decimal],
    existing_bushels: Decimal,
    incoming: Mapping[Factor, Decimal],
    incoming_bushels: Decimal,
) -> dict[Factor, Decimal]:
    """Weighted average of the two samples, on the factors both have."""
    if existing_bushels <= 0 or not existing:
        return dict(incoming)
    total = existing_bushels + incoming_bushels
    blended: dict[Factor, Decimal] = {}
    for factor in set(existing) & set(incoming):
        weighted = (
            Decimal(existing[factor]) * existing_bushels
            + Decimal(incoming[factor]) * incoming_bushels
        )
        blended[factor] = quantize(weighted / total, Decimal("0.01"))
    return blended


class BinYard:
    """Every bin at one site."""

    def __init__(self, location_code: str, bins: list[Bin] | None = None) -> None:
        self.location_code = location_code
        self._bins: dict[str, Bin] = {}
        for one in bins or []:
            self.add(one)

    def add(self, one: Bin) -> None:
        if one.bin_id in self._bins:
            raise ConflictError(f"{one.bin_id} is already in the yard")
        if one.location_code != self.location_code:
            raise BinError(
                f"{one.bin_id} is at {one.location_code}, not {self.location_code}"
            )
        self._bins[one.bin_id] = one

    def get(self, bin_id: str) -> Bin:
        try:
            return self._bins[bin_id]
        except KeyError as exc:
            raise BinError(f"no bin {bin_id} at {self.location_code}") from exc

    def replace(self, one: Bin) -> None:
        self.get(one.bin_id)
        self._bins[one.bin_id] = one

    def bins(self) -> tuple[Bin, ...]:
        return tuple(self._bins[key] for key in sorted(self._bins))

    def holding(self, commodity_code: str) -> tuple[Bin, ...]:
        return tuple(
            one for one in self.bins() if one.commodity_code == commodity_code
        )

    def total_bushels(self, commodity_code: str | None = None) -> Decimal:
        wanted = [
            one
            for one in self.bins()
            if commodity_code is None or one.commodity_code == commodity_code
        ]
        return quantize(sum((one.bushels for one in wanted), Decimal("0")), BUSHEL_PLACES)

    def room_for(self, commodity_code: str) -> Decimal:
        """Everywhere a load of this commodity could go."""
        return quantize(
            sum(
                (
                    one.headroom
                    for one in self.bins()
                    if not one.out_of_service
                    and one.commodity_code in (None, commodity_code)
                ),
                Decimal("0"),
            ),
            BUSHEL_PLACES,
        )

    def best_bin_for(self, commodity_code: str, bushels: Decimal) -> Bin | None:
        """Where to put a load.

        Into an open bin of the same commodity before an empty one, and the
        fullest of those first, so partly filled bins get finished rather than
        the yard ending up with nine bins a third full.
        """
        candidates = [one for one in self.bins() if one.accepts(commodity_code, bushels)]
        if not candidates:
            return None
        same = [one for one in candidates if one.commodity_code == commodity_code]
        if same:
            return max(same, key=lambda one: (one.bushels, one.bin_id))
        return min(candidates, key=lambda one: (one.capacity_bushels, one.bin_id))
