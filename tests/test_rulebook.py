from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.application import OverDelivery, SelectionRule
from bushelworks.domain.moisture import (
    DryingSchedule,
    DryingTier,
    ShrinkMethod,
    ShrinkSchedule,
)
from bushelworks.domain.rulebook import HousePolicy, RuleBook
from bushelworks.domain.storage import AccrualMethod, StorageRate
from bushelworks.errors import ConfigurationError

CORN_SHRINK_2023 = ShrinkSchedule(
    commodity_code="CORN",
    base_moisture=Decimal("15.0"),
    method=ShrinkMethod.TABLE,
    shrink_factor=Decimal("1.20"),
)

CORN_SHRINK_2024 = ShrinkSchedule(
    commodity_code="CORN",
    base_moisture=Decimal("15.0"),
    method=ShrinkMethod.TABLE,
    shrink_factor=Decimal("1.18"),
)

CORN_DRYER = DryingSchedule(
    commodity_code="CORN",
    tiers=[DryingTier(up_to_points=None, rate_per_point=Decimal("0.0450"))],
)

CORN_STORAGE = StorageRate(
    commodity_code="CORN",
    rate_per_bushel_per_month=Decimal("0.0450"),
    method=AccrualMethod.DAILY,
    free_days=30,
)


def book() -> RuleBook:
    rb = RuleBook(policy=HousePolicy(state_code="KS"))
    rb.file_shrink("CORN", CORN_SHRINK_2023, date(2023, 8, 1))
    rb.file_shrink("CORN", CORN_SHRINK_2024, date(2024, 8, 1))
    rb.file_drying("CORN", CORN_DRYER, date(2023, 8, 1))
    rb.file_storage("CORN", CORN_STORAGE, date(2023, 8, 1))
    return rb


def test_the_ticket_date_picks_the_shrink_table():
    rb = book()
    assert rb.shrink_for("CORN", date(2024, 7, 31)).shrink_factor == Decimal("1.20")
    assert rb.shrink_for("CORN", date(2024, 8, 1)).shrink_factor == Decimal("1.18")


def test_a_branch_shrink_table_wins_over_a_newer_company_one():
    rb = book()
    rb.file_shrink(
        "CORN",
        ShrinkSchedule(
            commodity_code="CORN",
            base_moisture=Decimal("15.0"),
            shrink_factor=Decimal("1.25"),
        ),
        date(2023, 9, 1),
        location_code="LURAY",
    )
    assert rb.shrink_for("CORN", date(2024, 10, 1), "LURAY").shrink_factor == Decimal(
        "1.25"
    )
    assert rb.shrink_for("CORN", date(2024, 10, 1), "HOME").shrink_factor == Decimal(
        "1.18"
    )


def test_drying_is_optional_and_missing_means_none():
    rb = book()
    assert rb.drying_for("CORN", date(2024, 10, 1)) is not None
    assert rb.drying_for("SOYB", date(2024, 10, 1)) is None


def test_storage_is_optional_too():
    rb = book()
    assert rb.storage_for("CORN", date(2024, 10, 1)) is not None
    assert rb.storage_for("HRW", date(2024, 10, 1)) is None


def test_covers_turns_on_the_shrink_table():
    rb = book()
    assert rb.covers("CORN", date(2024, 10, 1), "HOME")
    assert not rb.covers("SOYB", date(2024, 10, 1), "HOME")
    assert not rb.covers("CORN", date(2023, 1, 1), "HOME")


def test_a_schedule_cannot_be_filed_under_the_wrong_commodity():
    rb = RuleBook(policy=HousePolicy(state_code="KS"))
    with pytest.raises(ConfigurationError):
        rb.file_shrink("SOYB", CORN_SHRINK_2024, date(2024, 8, 1))
    with pytest.raises(ConfigurationError):
        rb.file_drying("SOYB", CORN_DRYER, date(2024, 8, 1))
    with pytest.raises(ConfigurationError):
        rb.file_storage("SOYB", CORN_STORAGE, date(2024, 8, 1))


def test_policy_defaults_are_the_conservative_ones():
    policy = HousePolicy(state_code="KS")
    assert policy.selection_rule is SelectionRule.WINDOW
    assert policy.over_delivery is OverDelivery.STORE
    assert policy.round_settlement_to_cents


def test_a_bad_state_code_is_refused():
    with pytest.raises(ConfigurationError):
        HousePolicy(state_code="KANSAS")


def test_a_negative_minimum_settlement_is_refused():
    with pytest.raises(ConfigurationError):
        HousePolicy(state_code="KS", minimum_settlement=Decimal("-1"))


def test_an_empty_book_still_answers_the_optional_lookups():
    rb = RuleBook(policy=HousePolicy(state_code="NE"))
    assert rb.drying_for("CORN", date(2024, 10, 1)) is None
    assert rb.storage_for("CORN", date(2024, 10, 1)) is None
    assert rb.discounts.factors("CORN") == ()
    assert rb.assessments.codes() == ()
