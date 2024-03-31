"""Turn a scale ticket into a graded, shrunk, discounted load.

This is the first half of settlement and the half that has no money in it yet.
It answers four questions in order and each answer feeds the next:

1. how many bushels came off the truck, after dockage
2. what grade the sample is
3. how many of those bushels survive moisture shrink
4. what the quality discounts come to, per bushel

Discounts are quoted per bushel here rather than in dollars because which bushel
count they are extended over is a settlement decision, not an intake one. Some
houses discount the wet bushels and some the dry, and the difference on a wet
harvest is real money.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from decimal import Decimal

from ..domain import commodity as cm
from ..domain import grade as gr
from ..domain.moisture import ShrinkSchedule
from ..domain.rulebook import RuleBook
from ..domain.tickets import ScaleTicket
from ..errors import ValidationError
from ..units import BUSHEL_PLACES, quantize


class IntakeError(ValidationError):
    """Raised when a ticket cannot be taken in as written."""

    code = "intake_error"


@dataclass(frozen=True, slots=True)
class GradedLoad:
    """What intake knows about a load before anybody prices it."""

    ticket_number: str
    commodity_code: str
    scale_date: date
    location_code: str
    net_pounds: Decimal
    dockage_pounds: Decimal
    gross_bushels: Decimal
    clean_bushels: Decimal
    dry_bushels: Decimal
    shrink_bushels: Decimal
    shrink_percent: Decimal
    moisture_points: Decimal
    grade: gr.GradeResult
    discount_rate: Decimal
    discount_detail: dict[cm.Factor, Decimal]

    @property
    def settled_bushels(self) -> Decimal:
        """The bushel count everything downstream prices."""
        return self.dry_bushels

    @property
    def is_sample_grade(self) -> bool:
        return self.grade.is_sample_grade


def receive(ticket: ScaleTicket, rules: RuleBook) -> GradedLoad:
    """Run one ticket through grading, shrink and the discount book."""
    if ticket.voided:
        raise IntakeError(f"{ticket.ticket_number}: ticket is voided")

    commodity = cm.get(ticket.commodity_code)
    if not rules.covers(ticket.commodity_code, ticket.scale_date, ticket.location_code):
        raise IntakeError(
            f"{ticket.ticket_number}: no shrink schedule on file for "
            f"{ticket.commodity_code} at {ticket.location_code} on {ticket.scale_date}"
        )

    grade_result = gr.determine(commodity, ticket.sample)
    schedule = rules.shrink_for(
        ticket.commodity_code, ticket.scale_date, ticket.location_code
    )
    moisture = ticket.require_factor(cm.Factor.MOISTURE)

    clean = ticket.clean_bushels(commodity)
    dry = schedule.dry_bushels(clean, moisture)
    shrunk = quantize(clean - dry, BUSHEL_PLACES)

    detail = rules.discounts.breakdown(ticket.commodity_code, ticket.sample)
    rate = rules.discounts.total_rate(ticket.commodity_code, ticket.sample)

    return GradedLoad(
        ticket_number=ticket.ticket_number,
        commodity_code=ticket.commodity_code,
        scale_date=ticket.scale_date,
        location_code=ticket.location_code,
        net_pounds=ticket.net_pounds,
        dockage_pounds=ticket.dockage_pounds,
        gross_bushels=ticket.gross_bushels(commodity),
        clean_bushels=clean,
        dry_bushels=dry,
        shrink_bushels=shrunk,
        shrink_percent=schedule.shrink_percent(moisture),
        moisture_points=schedule.points_over(moisture),
        grade=grade_result,
        discount_rate=rate,
        discount_detail=detail,
    )


def drying_charge(load: GradedLoad, rules: RuleBook, on_dry_bushels: bool = False) -> Decimal:
    """What the dryer costs on this load, if the house has one on file."""
    schedule = rules.drying_for(load.commodity_code, load.scale_date, load.location_code)
    if schedule is None or load.moisture_points <= 0:
        return Decimal("0.00")
    bushels = load.dry_bushels if on_dry_bushels else load.clean_bushels
    return schedule.charge(bushels, load.moisture_points)


def restate_for(load: GradedLoad, schedule: ShrinkSchedule, moisture: Decimal) -> GradedLoad:
    """Recompute shrink on a corrected moisture reading.

    The moisture meter is the instrument most likely to be wrong and the one
    most likely to be rechecked while the truck is still on the lot. Rather than
    void and rewrite the ticket, intake restates the load, and settlement picks
    up whichever version it is handed.
    """
    from dataclasses import replace

    dry = schedule.dry_bushels(load.clean_bushels, moisture)
    return replace(
        load,
        dry_bushels=dry,
        shrink_bushels=quantize(load.clean_bushels - dry, BUSHEL_PLACES),
        shrink_percent=schedule.shrink_percent(moisture),
        moisture_points=schedule.points_over(moisture),
    )
