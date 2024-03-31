"""Fixtures modelled on one real house so the numbers can be checked by hand.

The house is a two site country elevator in north central Kansas that buys corn,
soybeans and hard red winter wheat. Its filed tables are below. Every expected
value in the tests can be worked out from these with a pencil, which is the
point: a fixture nobody can recompute is a fixture nobody can argue with.
"""

from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.assessments import standard_book
from bushelworks.domain.commodity import Factor
from bushelworks.domain.discounts import (
    DiscountBand,
    DiscountBook,
    DiscountMode,
    DiscountSchedule,
)
from bushelworks.domain.moisture import (
    DryingSchedule,
    DryingTier,
    ShrinkMethod,
    ShrinkSchedule,
)
from bushelworks.domain.rulebook import HousePolicy, RuleBook
from bushelworks.domain.storage import AccrualMethod, StorageRate
from bushelworks.domain.tickets import Disposition, ScaleTicket, SplitShare

SEASON_OPENS = date(2024, 8, 1)


@pytest.fixture
def discount_book() -> DiscountBook:
    corn_test_weight = DiscountSchedule(
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
    )
    corn_bcfm = DiscountSchedule(
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
    )
    corn_damage = DiscountSchedule(
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
    )
    soy_foreign = DiscountSchedule(
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
    )
    return DiscountBook([corn_test_weight, corn_bcfm, corn_damage, soy_foreign])


@pytest.fixture
def rules(discount_book: DiscountBook) -> RuleBook:
    book = RuleBook(
        policy=HousePolicy(state_code="KS"),
        discounts=discount_book,
        assessments=standard_book(),
    )
    book.file_shrink(
        "CORN",
        ShrinkSchedule(
            commodity_code="CORN",
            base_moisture=Decimal("15.0"),
            method=ShrinkMethod.TABLE,
            shrink_factor=Decimal("1.18"),
            reject_above=Decimal("25.0"),
        ),
        SEASON_OPENS,
    )
    book.file_shrink(
        "SOYB",
        ShrinkSchedule(
            commodity_code="SOYB",
            base_moisture=Decimal("13.0"),
            method=ShrinkMethod.TABLE,
            shrink_factor=Decimal("1.15"),
        ),
        SEASON_OPENS,
    )
    book.file_shrink(
        "HRW",
        ShrinkSchedule(
            commodity_code="HRW",
            base_moisture=Decimal("13.5"),
            method=ShrinkMethod.NONE,
        ),
        SEASON_OPENS,
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
        SEASON_OPENS,
    )
    book.file_storage(
        "CORN",
        StorageRate(
            commodity_code="CORN",
            rate_per_bushel_per_month=Decimal("0.0450"),
            method=AccrualMethod.DAILY,
            free_days=30,
            in_charge_per_bushel=Decimal("0.0900"),
        ),
        SEASON_OPENS,
    )
    return book


@pytest.fixture
def corn_ticket() -> ScaleTicket:
    """52,240 lb net of wet corn, one owner, no dockage."""
    return ScaleTicket(
        ticket_number="A-10441",
        scale_date=date(2024, 10, 3),
        location_code="HOME",
        commodity_code="CORN",
        gross_pounds=Decimal("78420"),
        tare_pounds=Decimal("26180"),
        disposition=Disposition.CASH_SALE,
        splits=[SplitShare("MERRICK", Decimal("100"))],
        truck_id="T-14",
        driver="R. Merrick",
        farm="Home place",
        sample={
            Factor.TEST_WEIGHT: Decimal("56.5"),
            Factor.MOISTURE: Decimal("18.2"),
            Factor.BCFM: Decimal("1.2"),
            Factor.DAMAGE_TOTAL: Decimal("1.0"),
            Factor.DAMAGE_HEAT: Decimal("0.0"),
        },
    )
