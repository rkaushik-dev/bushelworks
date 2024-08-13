"""The charges and credits a settlement carries besides the grain itself.

Was ``settling.py``, which also held a ``settle_load`` that built a statement
for one ticket. Two builders existed for a while, one per ticket and one per
producer, and they drifted: the per producer one levied the checkoff once
across the statement and the per ticket one levied it per ticket, so the same
four loads settled to different money depending on which route the office took.

The per ticket builder is gone. What is left here are the value objects both of
them needed, which is all the API and the CLI ever imported from it.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from decimal import Decimal
from enum import Enum

from ..errors import ValidationError


class DiscountBasis(str, Enum):
    """Which bushel count quality discounts are extended over.

    A filed policy, not an arithmetic fact. Discounting the wet bushels costs
    the producer more than discounting the dry ones and both are in use.
    """

    WET = "wet"
    DRY = "dry"


class SettlementRefused(ValidationError):
    """Raised when a settlement cannot be produced from what is on hand."""

    code = "settlement_refused"


@dataclass(frozen=True, slots=True)
class Advance:
    """Money already paid against grain not yet settled."""

    reference: str
    dollars: Decimal
    advanced_on: date

    def __post_init__(self) -> None:
        if self.dollars <= 0:
            raise SettlementRefused(f"{self.reference}: an advance has to be positive")
        if not self.reference:
            raise SettlementRefused("an advance needs a reference")


@dataclass(frozen=True, slots=True)
class StorageCharge:
    """A storage bill already worked out by the warehousing service."""

    lot_id: str
    dollars: Decimal
    through: date

    def __post_init__(self) -> None:
        if self.dollars < 0:
            raise SettlementRefused(f"{self.lot_id}: a storage charge cannot be negative")


@dataclass(frozen=True, slots=True)
class Deduction:
    """Anything else the house takes off, with the reason on the statement.

    Freight the house paid on the producer's behalf, a lien assignment, a
    correction from a previous settlement. Always positive; the statement's line
    kind decides the sign.
    """

    code: str
    description: str
    dollars: Decimal

    def __post_init__(self) -> None:
        if self.dollars <= 0:
            raise SettlementRefused(f"{self.code}: a deduction has to be positive")
        if not self.description:
            raise SettlementRefused(f"{self.code}: a deduction needs a description")
