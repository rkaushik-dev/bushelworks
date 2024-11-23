"""Money paid to a producer before his grain is settled.

Two different things are both called an advance on the driveway and they behave
nothing alike:

* a **settlement advance** is part payment on grain the house already holds. It
  carries no interest, because the house has the grain.
* a **crop advance** is a loan against grain not yet delivered. It carries
  interest from the day it is drawn, because the house has nothing.

Both are recovered out of settlements. What differs is the interest and the
limit: a settlement advance is capped at a percentage of what the grain is
worth, and a crop advance is capped at whatever the manager agreed.

Interest is simple, not compound, on actual days over three hundred and sixty
five. That is what the note says and the note is what a court reads.
"""

from __future__ import annotations

from dataclasses import dataclass, field, replace
from datetime import date
from decimal import Decimal
from enum import Enum

from ..errors import ConflictError, ValidationError
from ..money import AMOUNT_PLACES, amount, total
from ..units import quantize

DAYS_IN_YEAR = Decimal("365")

# How much of a load's value the house will advance against grain it holds.
# Above this the house is lending against a price it has not agreed.
SETTLEMENT_ADVANCE_CAP = Decimal("80")


class AdvanceKind(str, Enum):
    SETTLEMENT = "settlement"
    CROP = "crop"


class AdvanceError(ValidationError):
    """Raised when an advance cannot be drawn or recovered as asked."""

    code = "advance_error"


@dataclass(frozen=True, slots=True)
class Repayment:
    """One recovery against an advance."""

    settlement_number: str
    dollars: Decimal
    repaid_on: date

    def __post_init__(self) -> None:
        if self.dollars <= 0:
            raise AdvanceError(
                f"{self.settlement_number}: a repayment has to be positive"
            )


@dataclass(frozen=True, slots=True)
class Advance:
    """One advance, with what has been recovered against it."""

    reference: str
    party_code: str
    kind: AdvanceKind
    principal: Decimal
    drawn_on: date
    annual_rate_percent: Decimal = Decimal("0")
    due_on: date | None = None
    repayments: tuple[Repayment, ...] = field(default_factory=tuple)

    def __post_init__(self) -> None:
        if not self.reference:
            raise AdvanceError("an advance needs a reference")
        if self.principal <= 0:
            raise AdvanceError(f"{self.reference}: an advance has to be positive")
        if self.annual_rate_percent < 0:
            raise AdvanceError(f"{self.reference}: a rate cannot be negative")
        if self.kind is AdvanceKind.SETTLEMENT and self.annual_rate_percent != 0:
            raise AdvanceError(
                f"{self.reference}: a settlement advance carries no interest"
            )
        if self.due_on is not None and self.due_on < self.drawn_on:
            raise AdvanceError(f"{self.reference}: due before it was drawn")

    @property
    def repaid(self) -> Decimal:
        return total(item.dollars for item in self.repayments)

    @property
    def outstanding(self) -> Decimal:
        left = amount(self.principal - self.repaid)
        return left if left > 0 else amount("0.00")

    @property
    def is_settled(self) -> bool:
        return self.outstanding == 0

    def days_out(self, on: date) -> int:
        if on < self.drawn_on:
            raise AdvanceError(f"{self.reference}: {on} is before it was drawn")
        return (on - self.drawn_on).days

    def interest_to(self, on: date) -> Decimal:
        """Simple interest on the outstanding principal to a date."""
        if self.annual_rate_percent == 0:
            return amount("0.00")
        days = Decimal(self.days_out(on))
        if days <= 0:
            return amount("0.00")
        accrued = (
            self.outstanding * self.annual_rate_percent / Decimal("100") * days
            / DAYS_IN_YEAR
        )
        return quantize(accrued, AMOUNT_PLACES)

    def payoff(self, on: date) -> Decimal:
        return amount(self.outstanding + self.interest_to(on))

    def is_overdue(self, on: date) -> bool:
        if self.due_on is None or self.is_settled:
            return False
        return on > self.due_on

    def repay(self, settlement_number: str, dollars: Decimal, on: date) -> Advance:
        wanted = amount(dollars)
        if wanted <= 0:
            raise AdvanceError(f"{self.reference}: cannot repay {wanted}")
        if self.is_settled:
            raise ConflictError(f"{self.reference} is already repaid")
        if wanted > self.outstanding:
            raise AdvanceError(
                f"{self.reference}: {wanted} is more than the {self.outstanding} "
                "outstanding"
            )
        return replace(
            self,
            repayments=(
                *self.repayments,
                Repayment(settlement_number, wanted, on),
            ),
        )

    def recoverable_from(self, net: Decimal, on: date) -> Decimal:
        """How much principal a settlement takes off this advance.

        Principal only. Interest is a charge on the statement in its own right
        and recovering it through here would make the repayment larger than the
        principal, which then reads as an overpayment on the note.

        A settlement never goes negative to service an advance; whatever it
        cannot cover stays outstanding and comes off the next one.
        """
        available = amount(net)
        if available <= 0:
            return amount("0.00")
        return amount(min(self.outstanding, available))


def cap_for(value: Decimal, percent: Decimal = SETTLEMENT_ADVANCE_CAP) -> Decimal:
    """The most the house will advance against grain worth ``value``."""
    if value < 0:
        raise AdvanceError("cannot advance against a negative value")
    return amount(Decimal(value) * percent / Decimal("100"))


def check_settlement_advance(
    principal: Decimal, grain_value: Decimal, percent: Decimal = SETTLEMENT_ADVANCE_CAP
) -> None:
    """Refuse an advance above the cap, naming both figures."""
    cap = cap_for(grain_value, percent)
    if amount(principal) > cap:
        raise AdvanceError(
            f"an advance of {amount(principal)} is above the {percent}% cap of {cap}"
        )


def outstanding_for(advances: list[Advance]) -> Decimal:
    return total(advance.outstanding for advance in advances)


def apply_to_settlement(
    advances: list[Advance], net: Decimal, settlement_number: str, on: date
) -> tuple[list[Advance], Decimal]:
    """Recover advances out of one settlement, oldest first.

    Returns the advances as they stand afterwards and what is left of the net.
    Oldest first because an advance that has been out longest is the one the
    lender asks about.
    """
    remaining = amount(net)
    if remaining < 0:
        # A settlement in debit recovers nothing and does not deepen the hole.
        remaining = amount("0.00")
    updated: list[Advance] = []
    for advance in sorted(advances, key=lambda a: (a.drawn_on, a.reference)):
        if advance.is_settled or remaining <= 0:
            updated.append(advance)
            continue
        take = advance.recoverable_from(remaining, on)
        if take <= 0:
            updated.append(advance)
            continue
        updated.append(advance.repay(settlement_number, take, on))
        remaining = amount(remaining - take)
    return updated, remaining
