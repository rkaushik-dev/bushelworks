"""Numerical grade determination from a probe sample.

The rule is worst-factor: a load grades no better than its poorest factor, so a
sample that would be a No. 1 on everything except test weight is a No. 3 if the
test weight only reaches the No. 3 line. Nothing averages, nothing trades off.

The limits below are the US standards the house grades against. They are stated
as an ordered ladder from best to worst, and determination walks the ladder from
the top and stops at the first rung the sample can stand on. A sample that falls
off the bottom is Sample Grade, which is not a grade so much as a refusal to
give one.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from decimal import Decimal
from enum import Enum

from ..errors import LookupFailure, ValidationError
from .commodity import FACTOR_DIRECTION, Commodity, Factor, FactorDirection


class NumericalGrade(str, Enum):
    """The rungs, best first. Ordering is by :attr:`rank`, not by name."""

    US_1 = "1"
    US_2 = "2"
    US_3 = "3"
    US_4 = "4"
    US_5 = "5"
    SAMPLE = "SAMPLE"

    @property
    def rank(self) -> int:
        return _GRADE_RANK[self]

    def is_better_than(self, other: NumericalGrade) -> bool:
        return self.rank < other.rank


_GRADE_RANK: Mapping[NumericalGrade, int] = {
    NumericalGrade.US_1: 1,
    NumericalGrade.US_2: 2,
    NumericalGrade.US_3: 3,
    NumericalGrade.US_4: 4,
    NumericalGrade.US_5: 5,
    NumericalGrade.SAMPLE: 99,
}


class IncompleteSample(ValidationError):
    """Raised when a required factor was never recorded on the sample."""


class NoGradeTable(LookupFailure):
    """Raised when a commodity has no grade ladder loaded."""


@dataclass(frozen=True, slots=True)
class GradeRung:
    """One rung of the ladder: a grade and the limits that reach it."""

    grade: NumericalGrade
    limits: Mapping[Factor, Decimal]

    def failures(self, sample: Mapping[Factor, Decimal]) -> tuple[Factor, ...]:
        """Factors on this sample that this rung will not accept."""
        bad: list[Factor] = []
        for factor, limit in self.limits.items():
            if factor not in sample:
                continue
            value = Decimal(sample[factor])
            if FACTOR_DIRECTION[factor] is FactorDirection.LOW_IS_BAD:
                if value < limit:
                    bad.append(factor)
            elif value > limit:
                bad.append(factor)
        return tuple(sorted(bad, key=lambda f: f.value))

    def accepts(self, sample: Mapping[Factor, Decimal]) -> bool:
        return not self.failures(sample)


@dataclass(frozen=True, slots=True)
class GradeResult:
    """What the grader ends up writing on the ticket."""

    grade: NumericalGrade
    limiting_factors: tuple[Factor, ...]

    @property
    def is_sample_grade(self) -> bool:
        return self.grade is NumericalGrade.SAMPLE


_LADDERS: dict[str, tuple[GradeRung, ...]] = {}


def register_ladder(commodity_code: str, rungs: Sequence[GradeRung]) -> None:
    ordered = tuple(sorted(rungs, key=lambda r: r.grade.rank))
    ranks = [r.grade.rank for r in ordered]
    if len(set(ranks)) != len(ranks):
        raise ValueError(f"{commodity_code}: duplicate grade on the ladder")
    _LADDERS[commodity_code] = ordered


def ladder(commodity_code: str) -> tuple[GradeRung, ...]:
    try:
        return _LADDERS[commodity_code]
    except KeyError as exc:
        raise NoGradeTable(commodity_code) from exc


def graded_factors(commodity_code: str) -> frozenset[Factor]:
    """Every factor any rung of this ladder looks at."""
    seen: set[Factor] = set()
    for rung in ladder(commodity_code):
        seen.update(rung.limits)
    return frozenset(seen)


def _require(commodity: Commodity, sample: Mapping[Factor, Decimal]) -> None:
    missing = commodity.required_factors - set(sample)
    if missing:
        names = ", ".join(sorted(f.value for f in missing))
        raise IncompleteSample(f"{commodity.code}: sample is missing {names}")


def determine(commodity: Commodity, sample: Mapping[Factor, Decimal]) -> GradeResult:
    """Walk the ladder top down and stop at the first rung that holds.

    ``limiting_factors`` names the factors that kept the sample off the rung
    above the one it landed on, which is what the grader tells the driver when
    he asks why his corn is a three.
    """
    _require(commodity, sample)
    rungs = ladder(commodity.code)

    previous: GradeRung | None = None
    for rung in rungs:
        if rung.accepts(sample):
            if previous is None:
                return GradeResult(rung.grade, ())
            return GradeResult(rung.grade, previous.failures(sample))
        previous = rung

    worst = rungs[-1]
    return GradeResult(NumericalGrade.SAMPLE, worst.failures(sample))


def factors_holding_back(
    commodity: Commodity,
    sample: Mapping[Factor, Decimal],
    target: NumericalGrade,
) -> tuple[Factor, ...]:
    """Which factors stop this sample reaching ``target``.

    Used by the blending desk. If a bin is one factor away from a No. 2 there
    is often something in the yard to blend it with, and knowing which factor
    is the difference between a useful call and a guess.
    """
    for rung in ladder(commodity.code):
        if rung.grade is target:
            return rung.failures(sample)
    raise NoGradeTable(f"{commodity.code} has no rung for grade {target.value}")


def _rungs(rows: Iterable[tuple[NumericalGrade, dict[Factor, str]]]) -> list[GradeRung]:
    return [
        GradeRung(grade, {factor: Decimal(value) for factor, value in limits.items()})
        for grade, limits in rows
    ]


register_ladder(
    "CORN",
    _rungs(
        [
            (
                NumericalGrade.US_1,
                {
                    Factor.TEST_WEIGHT: "56.0",
                    Factor.DAMAGE_HEAT: "0.1",
                    Factor.DAMAGE_TOTAL: "3.0",
                    Factor.BCFM: "2.0",
                },
            ),
            (
                NumericalGrade.US_2,
                {
                    Factor.TEST_WEIGHT: "54.0",
                    Factor.DAMAGE_HEAT: "0.2",
                    Factor.DAMAGE_TOTAL: "5.0",
                    Factor.BCFM: "3.0",
                },
            ),
            (
                NumericalGrade.US_3,
                {
                    Factor.TEST_WEIGHT: "52.0",
                    Factor.DAMAGE_HEAT: "0.5",
                    Factor.DAMAGE_TOTAL: "7.0",
                    Factor.BCFM: "4.0",
                },
            ),
            (
                NumericalGrade.US_4,
                {
                    Factor.TEST_WEIGHT: "49.0",
                    Factor.DAMAGE_HEAT: "1.0",
                    Factor.DAMAGE_TOTAL: "10.0",
                    Factor.BCFM: "5.0",
                },
            ),
            (
                NumericalGrade.US_5,
                {
                    Factor.TEST_WEIGHT: "46.0",
                    Factor.DAMAGE_HEAT: "3.0",
                    Factor.DAMAGE_TOTAL: "15.0",
                    Factor.BCFM: "7.0",
                },
            ),
        ]
    ),
)

register_ladder(
    "SOYB",
    _rungs(
        [
            (
                NumericalGrade.US_1,
                {
                    Factor.TEST_WEIGHT: "56.0",
                    Factor.DAMAGE_HEAT: "0.2",
                    Factor.DAMAGE_TOTAL: "2.0",
                    Factor.FOREIGN_MATERIAL: "1.0",
                    Factor.SPLITS: "10.0",
                },
            ),
            (
                NumericalGrade.US_2,
                {
                    Factor.TEST_WEIGHT: "54.0",
                    Factor.DAMAGE_HEAT: "0.5",
                    Factor.DAMAGE_TOTAL: "3.0",
                    Factor.FOREIGN_MATERIAL: "2.0",
                    Factor.SPLITS: "20.0",
                },
            ),
            (
                NumericalGrade.US_3,
                {
                    Factor.TEST_WEIGHT: "52.0",
                    Factor.DAMAGE_HEAT: "1.0",
                    Factor.DAMAGE_TOTAL: "5.0",
                    Factor.FOREIGN_MATERIAL: "3.0",
                    Factor.SPLITS: "30.0",
                },
            ),
            (
                NumericalGrade.US_4,
                {
                    Factor.TEST_WEIGHT: "49.0",
                    Factor.DAMAGE_HEAT: "3.0",
                    Factor.DAMAGE_TOTAL: "8.0",
                    Factor.FOREIGN_MATERIAL: "5.0",
                    Factor.SPLITS: "40.0",
                },
            ),
        ]
    ),
)

register_ladder(
    "HRW",
    _rungs(
        [
            (
                NumericalGrade.US_1,
                {
                    Factor.TEST_WEIGHT: "60.0",
                    Factor.DAMAGE_TOTAL: "2.0",
                    Factor.SHRUNKEN_BROKEN: "3.0",
                    Factor.TOTAL_DEFECTS: "3.0",
                },
            ),
            (
                NumericalGrade.US_2,
                {
                    Factor.TEST_WEIGHT: "58.0",
                    Factor.DAMAGE_TOTAL: "4.0",
                    Factor.SHRUNKEN_BROKEN: "5.0",
                    Factor.TOTAL_DEFECTS: "5.0",
                },
            ),
            (
                NumericalGrade.US_3,
                {
                    Factor.TEST_WEIGHT: "56.0",
                    Factor.DAMAGE_TOTAL: "7.0",
                    Factor.SHRUNKEN_BROKEN: "8.0",
                    Factor.TOTAL_DEFECTS: "8.0",
                },
            ),
            (
                NumericalGrade.US_4,
                {
                    Factor.TEST_WEIGHT: "54.0",
                    Factor.DAMAGE_TOTAL: "10.0",
                    Factor.SHRUNKEN_BROKEN: "12.0",
                    Factor.TOTAL_DEFECTS: "12.0",
                },
            ),
            (
                NumericalGrade.US_5,
                {
                    Factor.TEST_WEIGHT: "51.0",
                    Factor.DAMAGE_TOTAL: "15.0",
                    Factor.SHRUNKEN_BROKEN: "20.0",
                    Factor.TOTAL_DEFECTS: "20.0",
                },
            ),
        ]
    ),
)

register_ladder(
    "MILO",
    _rungs(
        [
            (
                NumericalGrade.US_1,
                {
                    Factor.TEST_WEIGHT: "57.0",
                    Factor.DAMAGE_HEAT: "0.2",
                    Factor.DAMAGE_TOTAL: "2.0",
                    Factor.FOREIGN_MATERIAL: "4.0",
                },
            ),
            (
                NumericalGrade.US_2,
                {
                    Factor.TEST_WEIGHT: "55.0",
                    Factor.DAMAGE_HEAT: "0.5",
                    Factor.DAMAGE_TOTAL: "5.0",
                    Factor.FOREIGN_MATERIAL: "8.0",
                },
            ),
            (
                NumericalGrade.US_3,
                {
                    Factor.TEST_WEIGHT: "53.0",
                    Factor.DAMAGE_HEAT: "1.0",
                    Factor.DAMAGE_TOTAL: "10.0",
                    Factor.FOREIGN_MATERIAL: "12.0",
                },
            ),
            (
                NumericalGrade.US_4,
                {
                    Factor.TEST_WEIGHT: "51.0",
                    Factor.DAMAGE_HEAT: "3.0",
                    Factor.DAMAGE_TOTAL: "15.0",
                    Factor.FOREIGN_MATERIAL: "15.0",
                },
            ),
        ]
    ),
)

register_ladder(
    "SUNF",
    _rungs(
        [
            (
                NumericalGrade.US_1,
                {Factor.DAMAGE_TOTAL: "5.0", Factor.FOREIGN_MATERIAL: "3.0"},
            ),
            (
                NumericalGrade.US_2,
                {Factor.DAMAGE_TOTAL: "10.0", Factor.FOREIGN_MATERIAL: "6.0"},
            ),
        ]
    ),
)
