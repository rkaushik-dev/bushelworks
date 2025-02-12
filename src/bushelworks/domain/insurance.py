"""Crop insurance, as far as the elevator is involved in it.

The house does not sell insurance and does not settle claims. What it does is
produce the numbers the adjuster asks for, and get them wrong at its peril: a
production report that disagrees with the settlement statements is the thing
that turns a claim into an investigation.

The adjuster wants production by unit, not by producer. A unit is a piece of
ground with an insurable history, and one producer usually farms several. The
link from a ticket to a unit is the farm code on the ticket, which is why the
scale house is told to key it even when nobody asks.

Everything here is in bushels as settled: after shrink, before discounts.
Discounts are a price adjustment and the adjuster does not want them.
"""

from __future__ import annotations

import csv
import io
from collections.abc import Sequence
from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal

from ..errors import ConflictError, ValidationError
from ..units import BUSHEL_PLACES, quantize


class InsuranceError(ValidationError):
    """Raised when a production report cannot be assembled."""

    code = "insurance_error"


@dataclass(frozen=True, slots=True)
class Unit:
    """One insurable unit: a piece of ground with a history."""

    unit_number: str
    party_code: str
    commodity_code: str
    crop_year: int
    acres: Decimal
    approved_yield: Decimal
    farm_codes: frozenset[str] = field(default_factory=frozenset)

    def __post_init__(self) -> None:
        if not self.unit_number:
            raise InsuranceError("a unit needs a number")
        if self.acres <= 0:
            raise InsuranceError(f"{self.unit_number}: a unit needs acres")
        if self.approved_yield <= 0:
            raise InsuranceError(f"{self.unit_number}: a unit needs an approved yield")

    @property
    def guaranteed_bushels(self) -> Decimal:
        """Acres times the approved yield, which is what a claim is measured against."""
        return quantize(self.acres * self.approved_yield, BUSHEL_PLACES)

    def covers(self, farm_code: str) -> bool:
        return farm_code in self.farm_codes


@dataclass(frozen=True, slots=True)
class Delivery:
    """One ticket, as the adjuster wants to see it."""

    ticket_number: str
    farm_code: str
    scale_date: date
    bushels: Decimal

    def __post_init__(self) -> None:
        if self.bushels < 0:
            raise InsuranceError(f"{self.ticket_number}: negative bushels")


@dataclass
class Production:
    """One unit's production for one crop year."""

    unit: Unit
    deliveries: list[Delivery] = field(default_factory=list)
    farm_stored: Decimal = Decimal("0")

    def add(self, delivery: Delivery) -> None:
        if not self.unit.covers(delivery.farm_code):
            raise ConflictError(
                f"{self.unit.unit_number} does not cover {delivery.farm_code}"
            )
        if any(item.ticket_number == delivery.ticket_number for item in self.deliveries):
            raise ConflictError(
                f"{delivery.ticket_number} is already on {self.unit.unit_number}"
            )
        self.deliveries.append(delivery)

    @property
    def delivered(self) -> Decimal:
        return quantize(
            sum((item.bushels for item in self.deliveries), Decimal("0")),
            BUSHEL_PLACES,
        )

    @property
    def total(self) -> Decimal:
        """Everything produced, including what never came to town."""
        return quantize(self.delivered + self.farm_stored, BUSHEL_PLACES)

    @property
    def yield_per_acre(self) -> Decimal:
        return quantize(self.total / self.unit.acres, Decimal("0.01"))

    @property
    def shortfall(self) -> Decimal:
        """Bushels below the guarantee. Zero is a good year, not an error."""
        gap = self.unit.guaranteed_bushels - self.total
        return quantize(gap, BUSHEL_PLACES) if gap > 0 else Decimal("0.0000")

    @property
    def has_a_claim(self) -> bool:
        return self.shortfall > 0

    @property
    def percent_of_guarantee(self) -> Decimal:
        guarantee = self.unit.guaranteed_bushels
        if guarantee <= 0:
            return Decimal("0.0")
        return quantize(self.total / guarantee * Decimal("100"), Decimal("0.1"))


def build(
    unit: Unit, deliveries: Sequence[Delivery], farm_stored: Decimal = Decimal("0")
) -> Production:
    """Gather the deliveries this unit covers, ignoring the rest."""
    production = Production(unit=unit, farm_stored=Decimal(farm_stored))
    for delivery in deliveries:
        if unit.covers(delivery.farm_code):
            production.add(delivery)
    return production


def to_csv(reports: Sequence[Production]) -> str:
    """The production report, in the shape the adjuster asks for."""
    buffer = io.StringIO()
    writer = csv.writer(buffer, lineterminator="\n")
    writer.writerow(
        [
            "unit_number",
            "party_code",
            "commodity_code",
            "crop_year",
            "acres",
            "approved_yield",
            "guaranteed_bushels",
            "delivered",
            "farm_stored",
            "total",
            "yield_per_acre",
            "shortfall",
        ]
    )
    for report in sorted(reports, key=lambda item: item.unit.unit_number):
        unit = report.unit
        writer.writerow(
            [
                unit.unit_number,
                unit.party_code,
                unit.commodity_code,
                unit.crop_year,
                unit.acres,
                unit.approved_yield,
                unit.guaranteed_bushels,
                report.delivered,
                report.farm_stored,
                report.total,
                report.yield_per_acre,
                report.shortfall,
            ]
        )
    return buffer.getvalue()


def claims(reports: Sequence[Production]) -> list[Production]:
    """The units short of their guarantee, biggest shortfall first."""
    found = [report for report in reports if report.has_a_claim]
    return sorted(found, key=lambda report: -report.shortfall)


def reconcile(report: Production, settled_bushels: Decimal) -> Decimal:
    """The gap between the production report and what was settled.

    Should be zero. Anything else means a ticket carries the wrong farm code,
    and the adjuster will find it before the office does.
    """
    return quantize(report.delivered - Decimal(settled_bushels), BUSHEL_PLACES)
