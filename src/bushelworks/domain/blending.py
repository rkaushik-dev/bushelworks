"""The blending desk.

A bin of No. 3 corn is worth less than a bin of No. 2, and if the house has
something clean to mix it with, the blend can be worth more than the parts.
Which factors blend and which do not is the whole question.

Factors that are a percentage of the sample blend by weight: damage, foreign
material, splits. Two thousand bushels at four percent damage mixed with two
thousand at one percent gives four thousand at two and a half.

Test weight does not blend by weight and pretending it does is the mistake this
module exists to stop. It is a density and it blends closer to a harmonic mean,
and the practical rule the trade uses is that a blend tests slightly below the
weighted average. This module applies the weighted average and then a penalty,
which is conservative in the direction that matters: the house discovers the
blend is better than predicted rather than worse.
"""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from typing import Mapping, Sequence

from ..errors import ValidationError
from ..units import BUSHEL_PLACES, quantize
from .commodity import Commodity, Factor
from .grade import GradeResult, NumericalGrade, determine

# Test weight comes in below the weighted average by about this much on a real
# blend. Measured across eleven blends in the 2023 harvest.
TEST_WEIGHT_PENALTY = Decimal("0.3")

BLENDS_BY_WEIGHT = frozenset(
    {
        Factor.MOISTURE,
        Factor.DOCKAGE,
        Factor.FOREIGN_MATERIAL,
        Factor.BCFM,
        Factor.DAMAGE_TOTAL,
        Factor.DAMAGE_HEAT,
        Factor.SPLITS,
        Factor.SHRUNKEN_BROKEN,
        Factor.TOTAL_DEFECTS,
        Factor.PROTEIN,
        Factor.OIL,
    }
)


class BlendError(ValidationError):
    """Raised when a blend cannot be worked out."""

    code = "blend_error"


@dataclass(frozen=True, slots=True)
class Lot:
    """Bushels of one quality, as a bin or a load."""

    label: str
    bushels: Decimal
    sample: Mapping[Factor, Decimal]

    def __post_init__(self) -> None:
        if self.bushels <= 0:
            raise BlendError(f"{self.label}: a lot needs bushels")
        if not self.sample:
            raise BlendError(f"{self.label}: a lot needs a sample")


@dataclass(frozen=True, slots=True)
class Blend:
    """What comes out of mixing several lots."""

    bushels: Decimal
    sample: dict[Factor, Decimal]
    lots: tuple[str, ...]

    def grade(self, commodity: Commodity) -> GradeResult:
        return determine(commodity, self.sample)


def blend(lots: Sequence[Lot]) -> Blend:
    """Mix lots and work out what the mixture tests."""
    if not lots:
        raise BlendError("nothing to blend")
    if len(lots) == 1:
        return Blend(
            bushels=quantize(lots[0].bushels, BUSHEL_PLACES),
            sample=dict(lots[0].sample),
            lots=(lots[0].label,),
        )

    total_bushels = sum((lot.bushels for lot in lots), Decimal("0"))
    factors: set[Factor] = set()
    for lot in lots:
        factors.update(lot.sample)

    mixed: dict[Factor, Decimal] = {}
    for factor in factors:
        present = [lot for lot in lots if factor in lot.sample]
        if len(present) != len(lots):
            # A factor only some lots were tested for cannot be blended. The
            # blend is reported without it rather than with a guess.
            continue
        weighted = sum(
            (Decimal(lot.sample[factor]) * lot.bushels for lot in lots), Decimal("0")
        )
        value = weighted / total_bushels
        if factor is Factor.TEST_WEIGHT:
            value -= TEST_WEIGHT_PENALTY
        elif factor not in BLENDS_BY_WEIGHT:
            continue
        mixed[factor] = quantize(value, Decimal("0.01"))

    return Blend(
        bushels=quantize(total_bushels, BUSHEL_PLACES),
        sample=mixed,
        lots=tuple(lot.label for lot in lots),
    )


def blend_to_grade(
    commodity: Commodity,
    poor: Lot,
    clean: Lot,
    target: NumericalGrade,
    step: Decimal = Decimal("100"),
) -> Decimal | None:
    """How many bushels of ``clean`` it takes to pull ``poor`` up to ``target``.

    Walks upward in steps rather than solving, because the grade ladder is a
    step function and the algebra would have to be redone every time a limit
    moves. Returns nothing if the clean lot is not big enough to do it.
    """
    if step <= 0:
        raise BlendError("a blending step has to be positive")

    used = Decimal("0")
    while used <= clean.bushels:
        if used > 0:
            portion = Lot(clean.label, used, clean.sample)
            mixture = blend([poor, portion])
        else:
            mixture = blend([poor])
        try:
            result = mixture.grade(commodity)
        except Exception as exc:  # a sample missing a required factor
            raise BlendError(f"cannot grade the blend: {exc}") from exc
        if not result.grade.is_better_than(target) and result.grade is not target:
            used = quantize(used + step, BUSHEL_PLACES)
            continue
        return used
    return None


def value_of(
    blended: Blend, commodity: Commodity, prices: Mapping[NumericalGrade, Decimal]
) -> Decimal:
    """What the blend is worth at the posted price for the grade it makes."""
    result = blended.grade(commodity)
    price = prices.get(result.grade)
    if price is None:
        raise BlendError(f"no price posted for grade {result.grade.value}")
    from ..money import extend

    return extend(blended.bushels, price)


def is_worth_blending(
    commodity: Commodity,
    lots: Sequence[Lot],
    prices: Mapping[NumericalGrade, Decimal],
) -> bool:
    """Whether mixing beats selling the lots separately.

    Almost always it does when one lot is off grade and almost never when both
    are on grade, but the desk asks anyway and the arithmetic is cheap.
    """
    separately = Decimal("0")
    for lot in lots:
        separately += value_of(blend([lot]), commodity, prices)
    return value_of(blend(lots), commodity, prices) > separately
