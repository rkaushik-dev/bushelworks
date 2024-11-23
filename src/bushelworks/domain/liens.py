"""Lienholders and joint payees.

A bank with a security interest in the crop tells the house so in writing, and
from then on the house is on the hook for the money if it pays the producer
alone. So the cheque goes out with both names on it, and which names and in what
order is not a formatting question.

Three arrangements, and they are not the same:

* **joint payee** puts both names on one cheque. The producer cannot cash it
  without the bank, and the bank cannot cash it without the producer.
* **assignment** sends an agreed amount straight to the assignee off the top,
  and the producer gets a cheque for what is left.
* **notice only** is a bank telling the house it has an interest without asking
  for anything. It goes on the file and changes nothing about the cheque, and it
  is the one people forget is different.

An assignment is capped at what it is written for. A joint payee is not capped
at anything, because it is not an amount.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from decimal import Decimal
from enum import Enum

from ..errors import ValidationError
from ..money import amount, total


class LienKind(str, Enum):
    JOINT_PAYEE = "joint_payee"
    ASSIGNMENT = "assignment"
    NOTICE_ONLY = "notice_only"


class LienError(ValidationError):
    """Raised when an interest cannot stand as filed."""

    code = "lien_error"


@dataclass(frozen=True, slots=True)
class Interest:
    """One filed security interest against a producer."""

    reference: str
    party_code: str
    holder: str
    kind: LienKind
    filed_on: date
    commodity_code: str | None = None
    amount_limit: Decimal | None = None
    released_on: date | None = None

    def __post_init__(self) -> None:
        if not self.reference:
            raise LienError("an interest needs a reference")
        if not self.holder:
            raise LienError(f"{self.reference}: an interest needs a holder")
        if self.kind is LienKind.ASSIGNMENT and self.amount_limit is None:
            raise LienError(f"{self.reference}: an assignment needs an amount")
        if self.amount_limit is not None and self.amount_limit <= 0:
            raise LienError(f"{self.reference}: an assignment has to be positive")
        if self.kind is LienKind.JOINT_PAYEE and self.amount_limit is not None:
            raise LienError(
                f"{self.reference}: a joint payee is not an amount and cannot "
                "carry a limit"
            )
        if self.released_on is not None and self.released_on < self.filed_on:
            raise LienError(f"{self.reference}: released before it was filed")

    def is_live_on(self, day: date) -> bool:
        if day < self.filed_on:
            return False
        return self.released_on is None or day < self.released_on

    def covers(self, commodity_code: str) -> bool:
        """A blanket interest covers everything; a specific one does not."""
        return self.commodity_code in (None, commodity_code)

    def applies_to(self, commodity_code: str, day: date) -> bool:
        return self.is_live_on(day) and self.covers(commodity_code)


@dataclass(frozen=True, slots=True)
class Payment:
    """One cheque the settlement produces."""

    payee: str
    joint_with: tuple[str, ...]
    dollars: Decimal
    reason: str

    @property
    def is_joint(self) -> bool:
        return bool(self.joint_with)

    @property
    def names(self) -> tuple[str, ...]:
        return (self.payee, *self.joint_with)


def live_interests(
    interests: list[Interest], commodity_code: str, day: date
) -> list[Interest]:
    """Everything that bites on this commodity on this day, oldest filing first."""
    found = [one for one in interests if one.applies_to(commodity_code, day)]
    return sorted(found, key=lambda one: (one.filed_on, one.reference))


def split_payment(
    party_code: str,
    net: Decimal,
    interests: list[Interest],
    commodity_code: str,
    day: date,
) -> list[Payment]:
    """Turn one net into the cheques that actually go out.

    Assignments come off the top, oldest filing first, each capped at what it is
    written for. Whatever is left goes to the producer, jointly with anybody who
    filed as a joint payee. A notice only interest changes nothing.
    """
    payable = amount(net)
    if payable <= 0:
        return []

    live = live_interests(interests, commodity_code, day)
    payments: list[Payment] = []
    remaining = payable

    for one in live:
        if one.kind is not LienKind.ASSIGNMENT or remaining <= 0:
            continue
        # An assignment always has a limit; the constructor refuses one without.
        limit = one.amount_limit or Decimal("0")
        take = amount(min(limit, remaining))
        if take <= 0:
            continue
        payments.append(
            Payment(
                payee=one.holder,
                joint_with=(),
                dollars=take,
                reason=f"assignment {one.reference}",
            )
        )
        remaining = amount(remaining - take)

    if remaining <= 0:
        return payments

    joint = tuple(
        one.holder for one in live if one.kind is LienKind.JOINT_PAYEE
    )
    payments.append(
        Payment(
            payee=party_code,
            joint_with=joint,
            dollars=remaining,
            reason="settlement",
        )
    )
    return payments


def check_balances(payments: list[Payment], net: Decimal) -> None:
    """Assert the cheques add back to the settlement, to the cent."""
    written = total(payment.dollars for payment in payments)
    expected = amount(net)
    if expected <= 0:
        expected = amount("0.00")
    if written != expected:
        raise LienError(
            f"cheques total {written}, which is not the {expected} settled"
        )


def notices(interests: list[Interest], day: date) -> list[Interest]:
    """Interests on file that ask for nothing, for the statement footer."""
    return [
        one
        for one in interests
        if one.kind is LienKind.NOTICE_ONLY and one.is_live_on(day)
    ]
