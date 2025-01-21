"""The grading bench, between the probe and the ticket.

A sample is drawn, split, tested and kept. The order is fixed and the keeping is
the part people skip: the retained half is what settles an argument three weeks
later, and a bench that has thrown it away has nothing to say.

A sample is identified by the probe number, not the ticket number. The ticket
does not exist yet when the probe is drawn, and tying the two together
afterwards is what the bench does.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field, replace
from datetime import date, datetime, timedelta
from decimal import Decimal
from enum import Enum

from ..errors import ConflictError, ValidationError
from .commodity import Commodity, Factor
from .grade import GradeResult, determine

# How long the retained half is kept. Long enough to cover a buyer's complaint
# and short enough that the bench does not fill up.
RETENTION = timedelta(days=45)


class Step(str, Enum):
    """Where a sample is on the bench."""

    DRAWN = "drawn"
    SPLIT = "split"
    TESTED = "tested"
    GRADED = "graded"
    DISCARDED = "discarded"


NEXT: dict[Step, tuple[Step, ...]] = {
    Step.DRAWN: (Step.SPLIT,),
    Step.SPLIT: (Step.TESTED,),
    Step.TESTED: (Step.GRADED,),
    Step.GRADED: (Step.DISCARDED,),
    Step.DISCARDED: (),
}


class BenchError(ValidationError):
    """Raised when a sample cannot be worked as asked."""

    code = "bench_error"


@dataclass(frozen=True, slots=True)
class Sample:
    """One probe, from the truck to the shelf."""

    probe_id: str
    commodity_code: str
    drawn_at: datetime
    drawn_by: str
    step: Step = Step.DRAWN
    ticket_number: str | None = None
    readings: Mapping[Factor, Decimal] = field(default_factory=dict)
    instrument: str | None = None
    retained: bool = False

    def __post_init__(self) -> None:
        if not self.probe_id:
            raise BenchError("a sample needs a probe number")
        if not self.drawn_by:
            raise BenchError(f"{self.probe_id}: a sample needs a grader")
        if self.step is Step.TESTED and not self.readings:
            raise BenchError(f"{self.probe_id}: tested but nothing was read")

    @property
    def discard_after(self) -> date:
        return (self.drawn_at + RETENTION).date()

    def is_retained_on(self, day: date) -> bool:
        return self.retained and day <= self.discard_after

    def _check_step(self, to: Step) -> None:
        if to not in NEXT[self.step]:
            raise ConflictError(
                f"{self.probe_id} is {self.step.value} and cannot go to {to.value}"
            )

    def advance(self, to: Step) -> Sample:
        self._check_step(to)
        return replace(self, step=to)

    def split(self, retain: bool = True) -> Sample:
        """Halve the probe, keeping one half on the shelf."""
        return replace(self.advance(Step.SPLIT), retained=retain)

    def test(self, readings: Mapping[Factor, Decimal], instrument: str) -> Sample:
        # The step and the readings move together. Stepping first would build a
        # tested sample with nothing on it, which the constructor refuses, and
        # rightly: that state is not a thing the bench can be in.
        self._check_step(Step.TESTED)
        if not readings:
            raise BenchError(f"{self.probe_id}: a test needs readings")
        if not instrument:
            raise BenchError(f"{self.probe_id}: a test needs an instrument")
        return replace(
            self,
            step=Step.TESTED,
            readings=dict(readings),
            instrument=instrument,
        )

    def grade(self, commodity: Commodity, ticket_number: str) -> tuple[Sample, GradeResult]:
        """Grade the sample and tie it to the ticket it belongs to."""
        if self.step is not Step.TESTED:
            raise ConflictError(
                f"{self.probe_id} is {self.step.value} and has not been tested"
            )
        if not ticket_number:
            raise BenchError(f"{self.probe_id}: a grade needs a ticket to sit on")
        if commodity.code != self.commodity_code:
            raise BenchError(
                f"{self.probe_id} is {self.commodity_code}, not {commodity.code}"
            )
        result = determine(commodity, self.readings)
        graded = replace(self.advance(Step.GRADED), ticket_number=ticket_number)
        return graded, result

    def discard(self, on: date) -> Sample:
        if not self.retained:
            return self.advance(Step.DISCARDED)
        if on <= self.discard_after:
            raise ConflictError(
                f"{self.probe_id} is retained until {self.discard_after}"
            )
        return replace(self.advance(Step.DISCARDED), retained=False)


class Bench:
    """Every sample on the bench at one site."""

    def __init__(self, location_code: str) -> None:
        self.location_code = location_code
        self._samples: dict[str, Sample] = {}

    def draw(self, sample: Sample) -> None:
        if sample.probe_id in self._samples:
            raise ConflictError(f"{sample.probe_id} is already on the bench")
        self._samples[sample.probe_id] = sample

    def get(self, probe_id: str) -> Sample:
        try:
            return self._samples[probe_id]
        except KeyError as exc:
            raise BenchError(f"{probe_id} is not on the bench") from exc

    def replace(self, sample: Sample) -> None:
        self.get(sample.probe_id)
        self._samples[sample.probe_id] = sample

    def waiting(self) -> tuple[Sample, ...]:
        """Samples drawn and not yet graded, oldest first."""
        found = [
            sample
            for sample in self._samples.values()
            if sample.step in (Step.DRAWN, Step.SPLIT, Step.TESTED)
        ]
        return tuple(sorted(found, key=lambda item: (item.drawn_at, item.probe_id)))

    def for_ticket(self, ticket_number: str) -> Sample | None:
        for sample in self._samples.values():
            if sample.ticket_number == ticket_number:
                return sample
        return None

    def retained_on(self, day: date) -> tuple[Sample, ...]:
        return tuple(
            sample for sample in self._samples.values() if sample.is_retained_on(day)
        )

    def due_for_discard(self, day: date) -> tuple[Sample, ...]:
        """Retained samples past their date, which is what clears the shelf."""
        return tuple(
            sample
            for sample in self._samples.values()
            if sample.retained and day > sample.discard_after
        )

    def depth(self) -> int:
        return len(self.waiting())
