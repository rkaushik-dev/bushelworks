"""Everything one house has filed, in one object.

Each piece of the settlement arithmetic already knows how to do its own job. What
it does not know is which version of itself applies to a given ticket, and that
depends on the commodity, the elevator and the date on the ticket rather than on
today. The rule book is the thing that holds all of those registries together
and answers one question: for this ticket, which tables?

Nothing here computes money. It hands back the tables that do, which keeps the
resolution rules in one place and testable on their own.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal

from ..errors import ConfigurationError
from ..schedules.registry import COMPANY_WIDE, ScheduleRegistry, Version
from .application import OverDelivery, SelectionRule
from .assessments import AssessmentBook
from .discounts import DiscountBook
from .moisture import DryingSchedule, ShrinkSchedule
from .storage import StorageRate


@dataclass(frozen=True, slots=True)
class HousePolicy:
    """The choices a house makes once and then lives with."""

    state_code: str
    selection_rule: SelectionRule = SelectionRule.WINDOW
    over_delivery: OverDelivery = OverDelivery.STORE
    round_settlement_to_cents: bool = True
    minimum_settlement: Decimal = Decimal("0.00")

    def __post_init__(self) -> None:
        if len(self.state_code) != 2:
            raise ConfigurationError(
                f"state code {self.state_code!r} should be two letters"
            )
        if self.minimum_settlement < 0:
            raise ConfigurationError("minimum settlement cannot be negative")


@dataclass
class RuleBook:
    """The filed tables for one house, resolved by commodity, site and date."""

    policy: HousePolicy
    discounts: DiscountBook = field(default_factory=DiscountBook)
    assessments: AssessmentBook = field(default_factory=AssessmentBook)
    shrink: ScheduleRegistry[ShrinkSchedule] = field(
        default_factory=lambda: ScheduleRegistry("shrink")
    )
    drying: ScheduleRegistry[DryingSchedule] = field(
        default_factory=lambda: ScheduleRegistry("drying")
    )
    storage: ScheduleRegistry[StorageRate] = field(
        default_factory=lambda: ScheduleRegistry("storage")
    )

    def file_shrink(
        self,
        commodity_code: str,
        schedule: ShrinkSchedule,
        effective_from: date,
        location_code: str = COMPANY_WIDE,
    ) -> None:
        if schedule.commodity_code != commodity_code:
            raise ConfigurationError(
                f"shrink schedule for {schedule.commodity_code} filed under "
                f"{commodity_code}"
            )
        self.shrink.file(
            commodity_code,
            Version(
                effective_from=effective_from,
                payload=schedule,
                location_code=location_code,
            ),
        )

    def file_drying(
        self,
        commodity_code: str,
        schedule: DryingSchedule,
        effective_from: date,
        location_code: str = COMPANY_WIDE,
    ) -> None:
        if schedule.commodity_code != commodity_code:
            raise ConfigurationError(
                f"drying schedule for {schedule.commodity_code} filed under "
                f"{commodity_code}"
            )
        self.drying.file(
            commodity_code,
            Version(
                effective_from=effective_from,
                payload=schedule,
                location_code=location_code,
            ),
        )

    def file_storage(
        self,
        commodity_code: str,
        rate: StorageRate,
        effective_from: date,
        location_code: str = COMPANY_WIDE,
    ) -> None:
        if rate.commodity_code != commodity_code:
            raise ConfigurationError(
                f"storage rate for {rate.commodity_code} filed under {commodity_code}"
            )
        self.storage.file(
            commodity_code,
            Version(
                effective_from=effective_from,
                payload=rate,
                location_code=location_code,
            ),
        )

    def shrink_for(
        self, commodity_code: str, as_of: date, location_code: str = COMPANY_WIDE
    ) -> ShrinkSchedule:
        return self.shrink.resolve(commodity_code, as_of, location_code)

    def drying_for(
        self, commodity_code: str, as_of: date, location_code: str = COMPANY_WIDE
    ) -> DryingSchedule | None:
        """Drying is optional. A house with no dryer files nothing and owes nothing."""
        return self.drying.maybe_resolve(commodity_code, as_of, location_code)

    def storage_for(
        self, commodity_code: str, as_of: date, location_code: str = COMPANY_WIDE
    ) -> StorageRate | None:
        return self.storage.maybe_resolve(commodity_code, as_of, location_code)

    def covers(self, commodity_code: str, as_of: date, location_code: str) -> bool:
        """Whether this house can settle the commodity at all on that day.

        Shrink is the test. A house can trade without a dryer, without storage
        and without a discount table, but it cannot turn wet bushels into dry
        ones without a filed shrink schedule, and settling on an unfiled table is
        how a house ends up refunding a whole harvest.
        """
        return (
            self.shrink.maybe_resolve(commodity_code, as_of, location_code) is not None
        )
