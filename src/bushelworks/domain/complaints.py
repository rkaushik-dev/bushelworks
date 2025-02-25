"""The complaint register.

A buyer refuses a car, a producer says his corn was better than the ticket says,
a feedlot rings about a load that smelled. All three are complaints and all
three have to be answered from records rather than memory.

The register exists to make two questions answerable:

* is this a one off, or has this bin been complained about three times
* was the retained sample still on the shelf when the complaint arrived

The second one decides whether the house can defend itself. A complaint that
arrives after the retention period is a complaint the house settles rather than
argues, and knowing that early saves a fortnight.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass, field, replace
from datetime import date, timedelta
from decimal import Decimal
from enum import Enum

from ..errors import ConflictError, ValidationError
from ..money import amount

# How long after a delivery a complaint is normally entertained.
NORMAL_WINDOW = timedelta(days=30)


class Source(str, Enum):
    BUYER = "buyer"
    PRODUCER = "producer"
    HAULIER = "haulier"


class Outcome(str, Enum):
    OPEN = "open"
    UPHELD = "upheld"
    REJECTED = "rejected"
    SETTLED = "settled"


class ComplaintError(ValidationError):
    """Raised when a complaint cannot be recorded or closed."""

    code = "complaint_error"


@dataclass(frozen=True, slots=True)
class Complaint:
    """One thing somebody rang up about."""

    reference: str
    source: Source
    raised_on: date
    delivered_on: date
    subject: str
    party_code: str | None = None
    bin_id: str | None = None
    ticket_number: str | None = None
    sale_number: str | None = None
    probe_id: str | None = None
    outcome: Outcome = Outcome.OPEN
    credit: Decimal = Decimal("0.00")
    closed_on: date | None = None
    note: str = ""

    def __post_init__(self) -> None:
        if not self.reference:
            raise ComplaintError("a complaint needs a reference")
        if not self.subject:
            raise ComplaintError(f"{self.reference}: a complaint needs a subject")
        if self.raised_on < self.delivered_on:
            raise ComplaintError(
                f"{self.reference}: raised before the grain was delivered"
            )
        if self.credit < 0:
            raise ComplaintError(f"{self.reference}: a credit cannot be negative")

    @property
    def days_after(self) -> int:
        return (self.raised_on - self.delivered_on).days

    @property
    def is_late(self) -> bool:
        """Raised outside the window the house normally entertains."""
        return self.raised_on > self.delivered_on + NORMAL_WINDOW

    @property
    def is_open(self) -> bool:
        return self.outcome is Outcome.OPEN

    def sample_is_available(self, retention_days: int = 45) -> bool:
        """Whether the retained half was still on the shelf when it arrived.

        Without it the house is arguing from a number it wrote down itself,
        which is not a position it wins from.
        """
        if self.probe_id is None:
            return False
        return self.days_after <= retention_days

    def close(
        self, outcome: Outcome, on: date, credit: Decimal = Decimal("0.00"), note: str = ""
    ) -> Complaint:
        if not self.is_open:
            raise ConflictError(
                f"{self.reference} was closed on {self.closed_on} as {self.outcome.value}"
            )
        if outcome is Outcome.OPEN:
            raise ComplaintError(f"{self.reference}: closing needs an outcome")
        if on < self.raised_on:
            raise ComplaintError(f"{self.reference}: closed before it was raised")
        if outcome is Outcome.REJECTED and credit > 0:
            raise ComplaintError(
                f"{self.reference}: a rejected complaint does not carry a credit"
            )
        return replace(
            self,
            outcome=outcome,
            closed_on=on,
            credit=amount(credit),
            note=note or self.note,
        )


@dataclass
class Register:
    """Every complaint, and what it lets the house see."""

    complaints: list[Complaint] = field(default_factory=list)

    def add(self, complaint: Complaint) -> None:
        if any(item.reference == complaint.reference for item in self.complaints):
            raise ConflictError(f"{complaint.reference} is already on the register")
        self.complaints.append(complaint)

    def replace(self, complaint: Complaint) -> None:
        for index, item in enumerate(self.complaints):
            if item.reference == complaint.reference:
                self.complaints[index] = complaint
                return
        raise ComplaintError(f"{complaint.reference} is not on the register")

    def open_complaints(self) -> tuple[Complaint, ...]:
        return tuple(
            sorted(
                (item for item in self.complaints if item.is_open),
                key=lambda item: (item.raised_on, item.reference),
            )
        )

    def for_bin(self, bin_id: str) -> tuple[Complaint, ...]:
        return tuple(item for item in self.complaints if item.bin_id == bin_id)

    def repeat_bins(self, threshold: int = 3) -> list[str]:
        """Bins complained about more than the house is comfortable with."""
        counts: dict[str, int] = {}
        for item in self.complaints:
            if item.bin_id:
                counts[item.bin_id] = counts.get(item.bin_id, 0) + 1
        return sorted(
            bin_id for bin_id, count in counts.items() if count >= threshold
        )

    def credits(self) -> Decimal:
        return amount(sum((item.credit for item in self.complaints), Decimal("0")))

    def upheld_rate(self) -> Decimal:
        closed = [item for item in self.complaints if not item.is_open]
        if not closed:
            return Decimal("0.0")
        upheld = [item for item in closed if item.outcome is Outcome.UPHELD]
        return (
            Decimal(len(upheld)) / Decimal(len(closed)) * Decimal("100")
        ).quantize(Decimal("0.1"))

    def indefensible(self, retention_days: int = 45) -> tuple[Complaint, ...]:
        """Open complaints with no sample to argue from."""
        return tuple(
            item
            for item in self.open_complaints()
            if not item.sample_is_available(retention_days)
        )


def by_source(complaints: Sequence[Complaint]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for item in complaints:
        counts[item.source.value] = counts.get(item.source.value, 0) + 1
    return dict(sorted(counts.items()))
