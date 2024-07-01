"""The commodity master.

Every other rule in the package hangs off a commodity code. The standard test
weight decides how many bushels a load is, the base moisture decides how much of
it shrinks away, and the factor set decides which columns the grader has to fill
in before a ticket can be graded at all.

These are US standards. A house that buys canola or field peas adds a row; it
does not change the shape of the row.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from decimal import Decimal
from enum import Enum

from ..errors import LookupFailure


class PriceUnit(str, Enum):
    """What the posted price is quoted per."""

    BUSHEL = "BU"
    HUNDREDWEIGHT = "CWT"
    TON = "TON"


class Factor(str, Enum):
    """Grade factors a grader can record on a probe sample.

    The names match the FGIS certificate columns rather than anything internal,
    because the grader is reading them off a certificate.
    """

    TEST_WEIGHT = "test_weight"
    MOISTURE = "moisture"
    DOCKAGE = "dockage"
    FOREIGN_MATERIAL = "foreign_material"
    BCFM = "bcfm"
    DAMAGE_TOTAL = "damage_total"
    DAMAGE_HEAT = "damage_heat"
    SPLITS = "splits"
    SHRUNKEN_BROKEN = "shrunken_broken"
    TOTAL_DEFECTS = "total_defects"
    PROTEIN = "protein"
    OIL = "oil"
    FALLING_NUMBER = "falling_number"


class FactorDirection(str, Enum):
    """Which way a factor has to move before it costs the producer money."""

    HIGH_IS_BAD = "high_is_bad"
    LOW_IS_BAD = "low_is_bad"


FACTOR_DIRECTION: Mapping[Factor, FactorDirection] = {
    Factor.TEST_WEIGHT: FactorDirection.LOW_IS_BAD,
    Factor.MOISTURE: FactorDirection.HIGH_IS_BAD,
    Factor.DOCKAGE: FactorDirection.HIGH_IS_BAD,
    Factor.FOREIGN_MATERIAL: FactorDirection.HIGH_IS_BAD,
    Factor.BCFM: FactorDirection.HIGH_IS_BAD,
    Factor.DAMAGE_TOTAL: FactorDirection.HIGH_IS_BAD,
    Factor.DAMAGE_HEAT: FactorDirection.HIGH_IS_BAD,
    Factor.SPLITS: FactorDirection.HIGH_IS_BAD,
    Factor.SHRUNKEN_BROKEN: FactorDirection.HIGH_IS_BAD,
    Factor.TOTAL_DEFECTS: FactorDirection.HIGH_IS_BAD,
    Factor.PROTEIN: FactorDirection.LOW_IS_BAD,
    Factor.OIL: FactorDirection.LOW_IS_BAD,
    Factor.FALLING_NUMBER: FactorDirection.LOW_IS_BAD,
}


class UnknownCommodity(LookupFailure):
    """Raised when a ticket names a commodity the house does not buy."""


@dataclass(frozen=True, slots=True)
class Commodity:
    """One row of the commodity master."""

    code: str
    name: str
    standard_test_weight: Decimal
    base_moisture: Decimal
    price_unit: PriceUnit
    required_factors: frozenset[Factor] = field(default_factory=frozenset)
    optional_factors: frozenset[Factor] = field(default_factory=frozenset)

    def __post_init__(self) -> None:
        if self.standard_test_weight <= 0:
            raise ValueError(f"{self.code}: standard test weight must be positive")
        if not (Decimal("0") < self.base_moisture < Decimal("100")):
            raise ValueError(f"{self.code}: base moisture out of range")
        overlap = self.required_factors & self.optional_factors
        if overlap:
            names = ", ".join(sorted(f.value for f in overlap))
            raise ValueError(f"{self.code}: factor listed twice: {names}")

    @property
    def graded_factors(self) -> frozenset[Factor]:
        return self.required_factors | self.optional_factors

    def accepts(self, factor: Factor) -> bool:
        return factor in self.graded_factors


_MASTER: dict[str, Commodity] = {}


def register(commodity: Commodity) -> Commodity:
    _MASTER[commodity.code] = commodity
    return commodity


def get(code: str) -> Commodity:
    try:
        return _MASTER[code]
    except KeyError as exc:
        raise UnknownCommodity(code) from exc


def codes() -> tuple[str, ...]:
    return tuple(sorted(_MASTER))


CORN = register(
    Commodity(
        code="CORN",
        name="Yellow Corn",
        standard_test_weight=Decimal("56"),
        base_moisture=Decimal("15.0"),
        price_unit=PriceUnit.BUSHEL,
        required_factors=frozenset(
            {Factor.TEST_WEIGHT, Factor.MOISTURE, Factor.BCFM, Factor.DAMAGE_TOTAL}
        ),
        optional_factors=frozenset({Factor.DAMAGE_HEAT}),
    )
)

SOYBEANS = register(
    Commodity(
        code="SOYB",
        name="Yellow Soybeans",
        standard_test_weight=Decimal("60"),
        base_moisture=Decimal("13.0"),
        price_unit=PriceUnit.BUSHEL,
        required_factors=frozenset(
            {
                Factor.TEST_WEIGHT,
                Factor.MOISTURE,
                Factor.FOREIGN_MATERIAL,
                Factor.DAMAGE_TOTAL,
                Factor.SPLITS,
            }
        ),
        optional_factors=frozenset({Factor.DAMAGE_HEAT, Factor.OIL}),
    )
)

HRW_WHEAT = register(
    Commodity(
        code="HRW",
        name="Hard Red Winter Wheat",
        standard_test_weight=Decimal("60"),
        base_moisture=Decimal("13.5"),
        price_unit=PriceUnit.BUSHEL,
        required_factors=frozenset(
            {
                Factor.TEST_WEIGHT,
                Factor.MOISTURE,
                Factor.DOCKAGE,
                Factor.DAMAGE_TOTAL,
                Factor.SHRUNKEN_BROKEN,
                Factor.TOTAL_DEFECTS,
            }
        ),
        optional_factors=frozenset(
            {Factor.PROTEIN, Factor.FALLING_NUMBER, Factor.DAMAGE_HEAT}
        ),
    )
)

GRAIN_SORGHUM = register(
    Commodity(
        code="MILO",
        name="Grain Sorghum",
        standard_test_weight=Decimal("56"),
        base_moisture=Decimal("14.0"),
        price_unit=PriceUnit.BUSHEL,
        required_factors=frozenset(
            {
                Factor.TEST_WEIGHT,
                Factor.MOISTURE,
                Factor.FOREIGN_MATERIAL,
                Factor.DAMAGE_TOTAL,
            }
        ),
    )
)

SUNFLOWER = register(
    Commodity(
        code="SUNF",
        name="Oil Sunflower Seed",
        standard_test_weight=Decimal("25"),
        base_moisture=Decimal("10.0"),
        price_unit=PriceUnit.HUNDREDWEIGHT,
        required_factors=frozenset(
            {Factor.MOISTURE, Factor.FOREIGN_MATERIAL, Factor.DAMAGE_TOTAL}
        ),
        optional_factors=frozenset({Factor.OIL}),
    )
)
