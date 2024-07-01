"""Checkoff assessments withheld at the first point of sale.

The house is the collecting agent. It withholds the assessment from the
producer, remits it to the commission and files a return, and it is liable for
the money whether or not it remembered to withhold it. That is why the rate
table lives in code with tests around it rather than in a spreadsheet on the
grain accountant's desk.

Two bases are in use. Per bushel is the common one. Percent of net value shows
up in the soybean orders and it is levied on the value *before* discounts in
some states and after in others, which is why the basis carries a flag for it
rather than being inferred.
"""

from __future__ import annotations

from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from decimal import Decimal
from enum import Enum

from ..errors import ValidationError
from ..money import amount, extend, total


class AssessmentBasis(str, Enum):
    PER_BUSHEL = "per_bushel"
    PERCENT_OF_VALUE = "percent_of_value"


class ValueBasis(str, Enum):
    """Which value a percentage assessment is levied on."""

    GROSS = "gross"
    NET_OF_DISCOUNTS = "net_of_discounts"


class AssessmentError(ValidationError):
    """Raised when an assessment cannot be worked out as configured."""


@dataclass(frozen=True, slots=True)
class Assessment:
    """One levy, as published by one commission."""

    code: str
    name: str
    authority: str
    commodity_code: str
    rate: Decimal
    basis: AssessmentBasis = AssessmentBasis.PER_BUSHEL
    value_basis: ValueBasis = ValueBasis.NET_OF_DISCOUNTS
    refundable: bool = False
    state_code: str = "*"

    def __post_init__(self) -> None:
        if self.rate < 0:
            raise AssessmentError(f"{self.code}: an assessment rate cannot be negative")
        if self.basis is AssessmentBasis.PERCENT_OF_VALUE and self.rate > Decimal("5"):
            raise AssessmentError(
                f"{self.code}: {self.rate}% is too large to be a checkoff"
            )

    def applies_in(self, state_code: str) -> bool:
        return self.state_code in ("*", state_code)

    def withhold(
        self,
        bushels: Decimal,
        gross_value: Decimal,
        net_value: Decimal,
    ) -> Decimal:
        """The dollars to withhold from this settlement for this levy."""
        if self.basis is AssessmentBasis.PER_BUSHEL:
            return extend(Decimal(bushels), self.rate)
        base = gross_value if self.value_basis is ValueBasis.GROSS else net_value
        if base <= 0:
            return amount("0.00")
        return amount(Decimal(base) * self.rate / Decimal("100"))


@dataclass(frozen=True, slots=True)
class WithheldLine:
    """One assessment as it appears on the settlement statement."""

    code: str
    name: str
    authority: str
    dollars: Decimal
    refundable: bool


class AssessmentBook:
    """Every levy the house collects, filtered by state and commodity."""

    def __init__(self, assessments: Sequence[Assessment] = ()) -> None:
        self._all: list[Assessment] = []
        for assessment in assessments:
            self.add(assessment)

    def add(self, assessment: Assessment) -> None:
        for existing in self._all:
            if (
                existing.code == assessment.code
                and existing.commodity_code == assessment.commodity_code
                and existing.state_code == assessment.state_code
            ):
                raise AssessmentError(f"{assessment.code} is already on the book")
        self._all.append(assessment)

    def applicable(self, commodity_code: str, state_code: str) -> list[Assessment]:
        found = [
            a
            for a in self._all
            if a.commodity_code == commodity_code and a.applies_in(state_code)
        ]
        return sorted(found, key=lambda a: (a.authority, a.code))

    def withhold(
        self,
        commodity_code: str,
        state_code: str,
        bushels: Decimal,
        gross_value: Decimal,
        net_value: Decimal,
        exempt_codes: Iterable[str] = (),
    ) -> list[WithheldLine]:
        """Every levy that applies, itemised, skipping the ones on file as exempt."""
        exempt = set(exempt_codes)
        lines: list[WithheldLine] = []
        for assessment in self.applicable(commodity_code, state_code):
            if assessment.code in exempt:
                continue
            dollars = assessment.withhold(bushels, gross_value, net_value)
            if dollars == 0:
                continue
            lines.append(
                WithheldLine(
                    code=assessment.code,
                    name=assessment.name,
                    authority=assessment.authority,
                    dollars=dollars,
                    refundable=assessment.refundable,
                )
            )
        return lines

    def total_withheld(self, lines: Sequence[WithheldLine]) -> Decimal:
        return total(line.dollars for line in lines)

    def refundable_portion(self, lines: Sequence[WithheldLine]) -> Decimal:
        """What the producer could ask back, which he almost never does."""
        return total(line.dollars for line in lines if line.refundable)

    def codes(self) -> tuple[str, ...]:
        return tuple(sorted({a.code for a in self._all}))


def standard_book() -> AssessmentBook:
    """The levies this house collects in Kansas and Nebraska."""
    return AssessmentBook(
        [
            Assessment(
                code="NCB",
                name="National Corn Checkoff",
                authority="National Corn Growers",
                commodity_code="CORN",
                rate=Decimal("0.0000"),
            ),
            Assessment(
                code="KSCORN",
                name="Kansas Corn Commission",
                authority="Kansas Department of Agriculture",
                commodity_code="CORN",
                rate=Decimal("0.0100"),
                state_code="KS",
                refundable=True,
            ),
            Assessment(
                code="NECORN",
                name="Nebraska Corn Board",
                authority="Nebraska Department of Agriculture",
                commodity_code="CORN",
                rate=Decimal("0.0050"),
                state_code="NE",
            ),
            Assessment(
                code="USB",
                name="United Soybean Board",
                authority="USDA",
                commodity_code="SOYB",
                rate=Decimal("0.5"),
                basis=AssessmentBasis.PERCENT_OF_VALUE,
                value_basis=ValueBasis.NET_OF_DISCOUNTS,
            ),
            Assessment(
                code="KSWHEAT",
                name="Kansas Wheat Commission",
                authority="Kansas Department of Agriculture",
                commodity_code="HRW",
                rate=Decimal("0.0200"),
                state_code="KS",
            ),
        ]
    )
