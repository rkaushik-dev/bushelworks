"""Official inspection certificates.

The house grades its own purchases. A sale to a terminal or an export elevator is
graded by a licensed inspector, and the certificate that comes back is the
document that decides what the buyer pays. When the two disagree the certificate
wins, and the difference between them is a real cost the house carries.

Certificates have a shape the house does not control:

* a certificate number issued by the agency, which is the key
* the factors as the inspector found them, which may include ones the house
  never measured
* a numerical grade and any special grades, which are words rather than numbers
* an expiry, because a certificate is only good for so long after the sample was
  drawn

The special grades are the part that surprises people. A load can be a US No. 2
and still be refused, because "US No. 2 Yellow Corn, Weevily" is not the same
thing as "US No. 2 Yellow Corn".
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import date, timedelta
from decimal import Decimal
from enum import Enum

from ..errors import ValidationError
from .commodity import Factor
from .grade import GradeResult, NumericalGrade

# How long an official certificate stands. After this the buyer can call for a
# reinspection and usually does.
CERTIFICATE_LIFE = timedelta(days=30)


class SpecialGrade(str, Enum):
    """The words that go after the number on a certificate."""

    INFESTED = "infested"
    WEEVILY = "weevily"
    HEATING = "heating"
    MUSTY = "musty"
    SOUR = "sour"
    LIGHT_DAMAGE = "light_damage"
    TREATED = "treated"
    GARLICKY = "garlicky"


# Special grades that let a buyer refuse a load outright rather than discount it.
REJECTING = frozenset(
    {
        SpecialGrade.INFESTED,
        SpecialGrade.WEEVILY,
        SpecialGrade.HEATING,
        SpecialGrade.TREATED,
    }
)


class InspectionError(ValidationError):
    """Raised when a certificate cannot stand as recorded."""

    code = "inspection_error"


@dataclass(frozen=True, slots=True)
class Certificate:
    """One official inspection, as the agency issued it."""

    number: str
    agency: str
    commodity_code: str
    sampled_on: date
    issued_on: date
    grade: NumericalGrade
    factors: Mapping[Factor, Decimal] = field(default_factory=dict)
    special_grades: frozenset[SpecialGrade] = field(default_factory=frozenset)
    reference: str | None = None

    def __post_init__(self) -> None:
        if not self.number:
            raise InspectionError("a certificate needs a number")
        if not self.agency:
            raise InspectionError(f"{self.number}: a certificate needs an agency")
        if self.issued_on < self.sampled_on:
            raise InspectionError(
                f"{self.number}: issued before the sample was drawn"
            )

    @property
    def expires_on(self) -> date:
        return self.sampled_on + CERTIFICATE_LIFE

    def is_current_on(self, day: date) -> bool:
        return self.issued_on <= day <= self.expires_on

    @property
    def is_rejectable(self) -> bool:
        """Whether a buyer can refuse the load outright on this certificate."""
        return bool(self.special_grades & REJECTING)

    @property
    def description(self) -> str:
        """What the certificate says, in the order it says it."""
        words = ", ".join(
            sorted(grade.value.replace("_", " ") for grade in self.special_grades)
        )
        base = f"US No. {self.grade.value} {self.commodity_code}"
        if self.grade is NumericalGrade.SAMPLE:
            base = f"Sample Grade {self.commodity_code}"
        return f"{base}, {words}" if words else base

    def factor(self, factor: Factor) -> Decimal | None:
        value = self.factors.get(factor)
        return None if value is None else Decimal(value)


@dataclass(frozen=True, slots=True)
class Disagreement:
    """Where the house grade and the certificate part company."""

    factor: Factor | None
    house: str
    official: str

    @property
    def is_grade(self) -> bool:
        return self.factor is None


def compare(house: GradeResult, certificate: Certificate) -> list[Disagreement]:
    """What the house called it against what the inspector called it.

    Factors the house never measured are not disagreements. It cannot disagree
    about a number it did not take, and reporting those as differences buries
    the ones that matter.
    """
    found: list[Disagreement] = []
    if house.grade is not certificate.grade:
        found.append(
            Disagreement(
                factor=None,
                house=house.grade.value,
                official=certificate.grade.value,
            )
        )
    return found


def compare_factors(
    house_sample: Mapping[Factor, Decimal],
    certificate: Certificate,
    tolerance: Mapping[Factor, Decimal] | None = None,
) -> list[Disagreement]:
    """Factor by factor, inside a tolerance the house sets per factor."""
    allowed = tolerance or {}
    found: list[Disagreement] = []
    for factor, official in sorted(
        certificate.factors.items(), key=lambda kv: kv[0].value
    ):
        ours = house_sample.get(factor)
        if ours is None:
            continue
        gap = abs(Decimal(ours) - Decimal(official))
        if gap > allowed.get(factor, Decimal("0")):
            found.append(
                Disagreement(factor=factor, house=str(ours), official=str(official))
            )
    return found


def grade_loss(
    house: GradeResult, certificate: Certificate, prices: Mapping[NumericalGrade, Decimal]
) -> Decimal:
    """What the difference in grade costs per bushel.

    Positive means the certificate came back worse than the house called it,
    which is the direction that costs money.
    """
    ours = prices.get(house.grade)
    theirs = prices.get(certificate.grade)
    if ours is None or theirs is None:
        raise InspectionError(
            "no posted price for one of the grades being compared"
        )
    return Decimal(ours) - Decimal(theirs)


def worst_of(certificates: list[Certificate]) -> Certificate:
    """The poorest certificate in a set, which is what a blend has to answer for."""
    if not certificates:
        raise InspectionError("no certificates to compare")
    return max(certificates, key=lambda one: (one.grade.rank, one.number))
