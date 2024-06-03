"""The filed tables this house is running on.

These are the schedules on file with the state for the 2024 crop year. They are
in code rather than in the database because they are versioned with the code
that reads them, and because a rate that changes needs a review and a deploy
rather than an UPDATE typed at four in the afternoon.

When the office wants to change one it files a new version with a later
effective date and leaves the old one alone. Nothing here is ever edited in
place; a ticket written last October has to settle on last October's table
forever.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal

from ..config import Settings
from ..domain.assessments import standard_book
from ..domain.commodity import Factor
from ..domain.discounts import (
    DiscountBand,
    DiscountBook,
    DiscountMode,
    DiscountSchedule,
)
from ..domain.moisture import (
    DryingSchedule,
    DryingTier,
    ShrinkMethod,
    ShrinkSchedule,
)
from ..domain.rulebook import HousePolicy, RuleBook
from ..domain.storage import AccrualMethod, StorageRate

CROP_YEAR_2023 = date(2023, 8, 1)
CROP_YEAR_2024 = date(2024, 8, 1)


def discount_book() -> DiscountBook:
    """Every discount schedule the house publishes on the board."""
    return DiscountBook(
        [
            DiscountSchedule(
                commodity_code="CORN",
                factor=Factor.TEST_WEIGHT,
                label="light test weight",
                bands=[
                    DiscountBand(
                        start=Decimal("54.0"),
                        end=Decimal("52.0"),
                        rate=Decimal("0.0100"),
                        increment=Decimal("0.5"),
                    ),
                    DiscountBand(
                        start=Decimal("52.0"),
                        rate=Decimal("0.0200"),
                        increment=Decimal("0.5"),
                    ),
                ],
            ),
            DiscountSchedule(
                commodity_code="CORN",
                factor=Factor.BCFM,
                label="broken corn and foreign material",
                bands=[
                    DiscountBand(
                        start=Decimal("3.0"),
                        rate=Decimal("0.0020"),
                        increment=Decimal("0.1"),
                        mode=DiscountMode.PRORATE,
                    )
                ],
            ),
            DiscountSchedule(
                commodity_code="CORN",
                factor=Factor.DAMAGE_TOTAL,
                label="total damage",
                bands=[
                    DiscountBand(
                        start=Decimal("5.0"),
                        rate=Decimal("0.0100"),
                        increment=Decimal("1.0"),
                    )
                ],
            ),
            DiscountSchedule(
                commodity_code="SOYB",
                factor=Factor.FOREIGN_MATERIAL,
                label="foreign material",
                bands=[
                    DiscountBand(
                        start=Decimal("1.0"),
                        rate=Decimal("0.0150"),
                        increment=Decimal("1.0"),
                    )
                ],
            ),
            DiscountSchedule(
                commodity_code="SOYB",
                factor=Factor.DAMAGE_TOTAL,
                label="total damage",
                bands=[
                    DiscountBand(
                        start=Decimal("3.0"),
                        rate=Decimal("0.0200"),
                        increment=Decimal("1.0"),
                    )
                ],
            ),
            DiscountSchedule(
                commodity_code="HRW",
                factor=Factor.TEST_WEIGHT,
                label="light test weight",
                bands=[
                    DiscountBand(
                        start=Decimal("58.0"),
                        rate=Decimal("0.0200"),
                        increment=Decimal("1.0"),
                    )
                ],
            ),
            DiscountSchedule(
                commodity_code="HRW",
                factor=Factor.DOCKAGE,
                label="dockage over one percent",
                bands=[
                    DiscountBand(
                        start=Decimal("1.0"),
                        rate=Decimal("0.0100"),
                        increment=Decimal("0.5"),
                        mode=DiscountMode.PRORATE,
                    )
                ],
            ),
        ]
    )


def corn_shrink(factor: str) -> ShrinkSchedule:
    return ShrinkSchedule(
        commodity_code="CORN",
        base_moisture=Decimal("15.0"),
        method=ShrinkMethod.TABLE,
        shrink_factor=Decimal(factor),
        reject_above=Decimal("25.0"),
    )


def house_rulebook(settings: Settings | None = None) -> RuleBook:
    """Build the rule book for this house, with both crop years filed."""
    state_code = settings.state_code if settings else "KS"
    minimum = settings.minimum_settlement if settings else Decimal("0.00")

    book = RuleBook(
        policy=HousePolicy(state_code=state_code, minimum_settlement=minimum),
        discounts=discount_book(),
        assessments=standard_book(),
    )

    book.file_shrink("CORN", corn_shrink("1.20"), CROP_YEAR_2023)
    book.file_shrink("CORN", corn_shrink("1.18"), CROP_YEAR_2024)
    book.file_shrink(
        "SOYB",
        ShrinkSchedule(
            commodity_code="SOYB",
            base_moisture=Decimal("13.0"),
            method=ShrinkMethod.TABLE,
            shrink_factor=Decimal("1.15"),
        ),
        CROP_YEAR_2023,
    )
    book.file_shrink(
        "HRW",
        ShrinkSchedule(
            commodity_code="HRW",
            base_moisture=Decimal("13.5"),
            method=ShrinkMethod.NONE,
        ),
        CROP_YEAR_2023,
    )

    book.file_drying(
        "CORN",
        DryingSchedule(
            commodity_code="CORN",
            tiers=[
                DryingTier(up_to_points=Decimal("2"), rate_per_point=Decimal("0.0300")),
                DryingTier(up_to_points=Decimal("5"), rate_per_point=Decimal("0.0450")),
                DryingTier(up_to_points=None, rate_per_point=Decimal("0.0600")),
            ],
        ),
        CROP_YEAR_2023,
    )

    for code in ("CORN", "SOYB", "HRW"):
        book.file_storage(
            code,
            StorageRate(
                commodity_code=code,
                rate_per_bushel_per_month=Decimal("0.0450"),
                method=AccrualMethod.DAILY,
                free_days=30,
                in_charge_per_bushel=Decimal("0.0900"),
            ),
            CROP_YEAR_2023,
        )

    return book
