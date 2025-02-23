"""Weights and measures, and what happens when a scale is out.

A truck scale is certified annually by the state and tested by the house in
between. When a test comes back outside tolerance, every ticket written since
the last good test is in question, and the house has to be able to say which
ones those are and what the error would have done to them.

That last part is what makes this worth modelling rather than filing on paper.
A scale reading two hundred pounds light for three weeks is a specific amount
of money owed to a specific list of producers, and working it out from a filing
cabinet takes a fortnight.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass, field
from datetime import date, timedelta
from decimal import Decimal
from enum import Enum

from ..errors import ValidationError
from ..units import quantize

# What the state allows on a vehicle scale, as a fraction of the test load.
TOLERANCE = Decimal("0.001")

# How long a certification stands.
CERTIFICATION_LIFE = timedelta(days=365)


class Result(str, Enum):
    PASS = "pass"
    FAIL = "fail"
    ADJUSTED = "adjusted"


class CertificationError(ValidationError):
    """Raised when a test or a certificate cannot stand."""

    code = "certification_error"


@dataclass(frozen=True, slots=True)
class ScaleTest:
    """One test of one scale with known weights.

    Named ``ScaleTest`` rather than ``Test`` because pytest tries to collect
    anything called ``Test`` and then complains that it has a constructor.
    """

    scale_id: str
    location_code: str
    tested_on: date
    test_load_pounds: Decimal
    indicated_pounds: Decimal
    tested_by: str
    result: Result = Result.PASS
    certificate_number: str | None = None

    def __post_init__(self) -> None:
        if self.test_load_pounds <= 0:
            raise CertificationError(f"{self.scale_id}: a test needs a known load")
        if self.indicated_pounds < 0:
            raise CertificationError(f"{self.scale_id}: an indicated weight cannot be negative")
        if not self.tested_by:
            raise CertificationError(f"{self.scale_id}: a test needs a tester")

    @property
    def error_pounds(self) -> Decimal:
        """Indicated less known. Positive means the scale reads heavy."""
        return quantize(self.indicated_pounds - self.test_load_pounds, Decimal("1"))

    @property
    def error_fraction(self) -> Decimal:
        return quantize(
            self.error_pounds / self.test_load_pounds, Decimal("0.000001")
        )

    @property
    def is_within_tolerance(self) -> bool:
        return abs(self.error_fraction) <= TOLERANCE

    @property
    def is_official(self) -> bool:
        return self.certificate_number is not None

    def expires_on(self) -> date | None:
        if not self.is_official:
            return None
        return self.tested_on + CERTIFICATION_LIFE

    def is_current_on(self, day: date) -> bool:
        expiry = self.expires_on()
        if expiry is None:
            return False
        return self.tested_on <= day <= expiry


@dataclass
class Log:
    """Every test of one scale, oldest first."""

    scale_id: str
    location_code: str
    tests: list[ScaleTest] = field(default_factory=list)

    def record(self, test: ScaleTest) -> None:
        if test.scale_id != self.scale_id:
            raise CertificationError(
                f"{test.scale_id} does not belong on the {self.scale_id} log"
            )
        self.tests.append(test)
        self.tests.sort(key=lambda item: item.tested_on)

    def latest(self) -> ScaleTest | None:
        return self.tests[-1] if self.tests else None

    def last_good_before(self, day: date) -> ScaleTest | None:
        """The most recent passing test before a day."""
        passing = [
            test
            for test in self.tests
            if test.tested_on <= day and test.is_within_tolerance
        ]
        return passing[-1] if passing else None

    def certified_on(self, day: date) -> bool:
        return any(test.is_current_on(day) for test in self.tests)

    def suspect_window(self, failed: ScaleTest) -> tuple[date, date] | None:
        """The span whose tickets are in question after a failed test.

        From the day after the last good test to the day the failure was found.
        A failure with no good test before it puts the whole log in question and
        this says so by returning nothing.
        """
        if failed.is_within_tolerance:
            return None
        good = self.last_good_before(failed.tested_on - timedelta(days=1))
        if good is None:
            return None
        return (good.tested_on + timedelta(days=1), failed.tested_on)


def correction_for(test: ScaleTest, net_pounds: Decimal) -> Decimal:
    """What a load weighed on an out of tolerance scale should have been.

    A scale reading heavy overpaid the producer, so the correction is negative.
    Applying it is a decision for the manager and the state, not for software.
    """
    if test.test_load_pounds <= 0:
        raise CertificationError(f"{test.scale_id}: no test load to correct against")
    return quantize(
        -Decimal(net_pounds) * test.error_fraction, Decimal("1")
    )


def exposure(test: ScaleTest, net_pounds_in_window: Decimal) -> Decimal:
    """Total pounds in question across the suspect window."""
    return abs(correction_for(test, net_pounds_in_window))


def overdue_certifications(logs: Sequence[Log], day: date) -> list[Log]:
    """Scales the state would not let the house run today."""
    return [log for log in logs if not log.certified_on(day)]
