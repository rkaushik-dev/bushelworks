"""The grain bank, which is a feed account and not a storage account.

A cattleman hauls corn in and takes ground feed out. He is not selling and the
house is not buying: it is holding his bushels and giving them back processed,
minus a processing charge and a shrink for what the mill loses.

The distinction from ordinary storage is the one that matters at settlement:

* storage bushels can be priced and turned into a cheque
* grain bank bushels can only be taken back as feed, and a producer who wants
  money has to convert the account to storage first, which is a decision with a
  date on it

Withdrawals are measured in tons of finished feed, not bushels of corn, and the
conversion is the ration's inclusion rate. A ration that is eighty percent corn
takes one and a quarter tons of corn out of the account per ton of feed.
"""

from __future__ import annotations

from dataclasses import dataclass, field, replace
from datetime import date
from decimal import Decimal

from ..errors import ConflictError, ValidationError
from ..money import amount, extend
from ..units import BUSHEL_PLACES, POUNDS_PER_TON, quantize

# What the mill loses grinding and mixing, as a percentage of what goes in.
MILL_SHRINK_PERCENT = Decimal("0.5")


class GrainBankError(ValidationError):
    """Raised when a feed account cannot do what is asked."""

    code = "grain_bank_error"


@dataclass(frozen=True, slots=True)
class Ration:
    """One feed formula, by how much of the account's grain it uses."""

    code: str
    name: str
    inclusion_percent: Decimal
    processing_per_ton: Decimal
    standard_test_weight: Decimal = Decimal("56")

    def __post_init__(self) -> None:
        if not (Decimal("0") < self.inclusion_percent <= Decimal("100")):
            raise GrainBankError(f"{self.code}: inclusion out of range")
        if self.processing_per_ton < 0:
            raise GrainBankError(f"{self.code}: processing cannot be negative")

    def bushels_for(self, tons: Decimal) -> Decimal:
        """Bushels of the producer's corn one order of feed takes out."""
        if tons <= 0:
            raise GrainBankError(f"{self.code}: cannot make {tons} tons")
        grain_pounds = (
            Decimal(tons) * POUNDS_PER_TON * self.inclusion_percent / Decimal("100")
        )
        with_shrink = grain_pounds * (
            Decimal("100") + MILL_SHRINK_PERCENT
        ) / Decimal("100")
        return quantize(with_shrink / self.standard_test_weight, BUSHEL_PLACES)

    def processing_charge(self, tons: Decimal) -> Decimal:
        return extend(Decimal(tons), self.processing_per_ton)


@dataclass(frozen=True, slots=True)
class Movement:
    """One deposit or withdrawal on a feed account."""

    reference: str
    on: date
    bushels: Decimal
    tons: Decimal | None = None
    ration_code: str | None = None
    charge: Decimal = Decimal("0.00")

    @property
    def is_deposit(self) -> bool:
        return self.bushels > 0

    def __post_init__(self) -> None:
        if self.bushels == 0:
            raise GrainBankError(f"{self.reference}: a movement of nothing")
        if not self.is_deposit and self.tons is None:
            raise GrainBankError(f"{self.reference}: a withdrawal is measured in tons")


@dataclass(frozen=True, slots=True)
class FeedAccount:
    """One cattleman's account at one site."""

    party_code: str
    commodity_code: str
    location_code: str
    opened_on: date
    movements: tuple[Movement, ...] = field(default_factory=tuple)
    converted_on: date | None = None

    @property
    def balance(self) -> Decimal:
        return quantize(
            sum((item.bushels for item in self.movements), Decimal("0")),
            BUSHEL_PLACES,
        )

    @property
    def is_converted(self) -> bool:
        return self.converted_on is not None

    @property
    def charges(self) -> Decimal:
        return amount(sum((item.charge for item in self.movements), Decimal("0")))

    def deposit(self, reference: str, bushels: Decimal, on: date) -> FeedAccount:
        if self.is_converted:
            raise ConflictError(
                f"{self.party_code}: the account was converted on {self.converted_on}"
            )
        wanted = quantize(Decimal(bushels), BUSHEL_PLACES)
        if wanted <= 0:
            raise GrainBankError(f"{reference}: a deposit has to be positive")
        return replace(
            self,
            movements=(*self.movements, Movement(reference, on, wanted)),
        )

    def withdraw(
        self, reference: str, ration: Ration, tons: Decimal, on: date
    ) -> FeedAccount:
        if self.is_converted:
            raise ConflictError(
                f"{self.party_code}: the account was converted on {self.converted_on}"
            )
        needed = ration.bushels_for(tons)
        if needed > self.balance:
            raise GrainBankError(
                f"{reference}: {needed} bushels needed and only {self.balance} on hand"
            )
        return replace(
            self,
            movements=(
                *self.movements,
                Movement(
                    reference=reference,
                    on=on,
                    bushels=-needed,
                    tons=Decimal(tons),
                    ration_code=ration.code,
                    charge=ration.processing_charge(tons),
                ),
            ),
        )

    def convert_to_storage(self, on: date) -> FeedAccount:
        """Close the feed account so the balance can be priced.

        A decision with a date, because it changes what the bushels are: before
        it they are feed and after it they are grain the producer can sell.
        """
        if self.is_converted:
            raise ConflictError(f"{self.party_code}: already converted")
        if self.balance <= 0:
            raise GrainBankError(
                f"{self.party_code}: nothing in the account to convert"
            )
        return replace(self, converted_on=on)

    def tons_taken(self) -> Decimal:
        return quantize(
            sum(
                (item.tons for item in self.movements if item.tons is not None),
                Decimal("0"),
            ),
            Decimal("0.001"),
        )

    def activity(self, since: date) -> tuple[Movement, ...]:
        return tuple(item for item in self.movements if item.on >= since)


def standard_rations() -> dict[str, Ration]:
    """The three the mill runs."""
    return {
        ration.code: ration
        for ration in (
            Ration(
                code="GROW",
                name="Growing ration",
                inclusion_percent=Decimal("70"),
                processing_per_ton=Decimal("14.00"),
            ),
            Ration(
                code="FIN",
                name="Finishing ration",
                inclusion_percent=Decimal("85"),
                processing_per_ton=Decimal("12.50"),
            ),
            Ration(
                code="CRACK",
                name="Cracked corn",
                inclusion_percent=Decimal("100"),
                processing_per_ton=Decimal("8.00"),
            ),
        )
    }
