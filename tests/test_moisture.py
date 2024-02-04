from decimal import Decimal

import pytest

from bushelworks.domain.moisture import (
    DryingBasis,
    DryingSchedule,
    DryingTier,
    MoistureError,
    ShrinkMethod,
    ShrinkSchedule,
)

CORN_TABLE = ShrinkSchedule(
    commodity_code="CORN",
    base_moisture=Decimal("15.0"),
    method=ShrinkMethod.TABLE,
    shrink_factor=Decimal("1.18"),
)

CORN_WATER = ShrinkSchedule(
    commodity_code="CORN",
    base_moisture=Decimal("15.0"),
    method=ShrinkMethod.WATER,
    handling_loss_percent=Decimal("0.5"),
)


def test_dry_grain_does_not_shrink():
    assert CORN_TABLE.points_over(Decimal("12.4")) == Decimal("0")
    assert CORN_TABLE.shrink_percent(Decimal("12.4")) == Decimal("0.0000")


def test_at_base_moisture_there_is_no_shrink():
    assert CORN_TABLE.shrink_percent(Decimal("15.0")) == Decimal("0.0000")


def test_table_shrink_is_points_times_factor():
    # 3.2 points over base at 1.18 per point
    assert CORN_TABLE.shrink_percent(Decimal("18.2")) == Decimal("3.7760")


def test_bare_water_shrink_is_smaller_than_the_table_at_the_same_moisture():
    bare = ShrinkSchedule(
        commodity_code="CORN",
        base_moisture=Decimal("15.0"),
        method=ShrinkMethod.WATER,
    )
    table = CORN_TABLE.shrink_percent(Decimal("18.2"))
    water = bare.shrink_percent(Decimal("18.2"))
    assert water == Decimal("3.7647")
    assert water < table


def test_water_shrink_includes_the_handling_loss():
    # 3.2 / 85 * 100 = 3.7647, plus 0.5 handling
    assert CORN_WATER.shrink_percent(Decimal("18.2")) == Decimal("4.2647")


def test_none_method_never_shrinks():
    schedule = ShrinkSchedule(
        commodity_code="SOYB",
        base_moisture=Decimal("13.0"),
        method=ShrinkMethod.NONE,
    )
    assert schedule.shrink_percent(Decimal("18.0")) == Decimal("0.0000")


def test_dry_and_shrunk_bushels_add_back_to_wet():
    wet = Decimal("1000.0000")
    dry = CORN_TABLE.dry_bushels(wet, Decimal("18.2"))
    gone = CORN_TABLE.shrunk_bushels(wet, Decimal("18.2"))
    assert dry == Decimal("962.2400")
    assert dry + gone == wet


def test_moisture_above_the_house_limit_is_refused():
    schedule = ShrinkSchedule(
        commodity_code="CORN",
        base_moisture=Decimal("15.0"),
        reject_above=Decimal("25.0"),
    )
    with pytest.raises(MoistureError):
        schedule.shrink_percent(Decimal("26.4"))


def test_nonsense_moisture_is_refused():
    with pytest.raises(MoistureError):
        CORN_TABLE.shrink_percent(Decimal("-1"))
    with pytest.raises(MoistureError):
        CORN_TABLE.shrink_percent(Decimal("100"))


DRYER = DryingSchedule(
    commodity_code="CORN",
    tiers=[
        DryingTier(up_to_points=Decimal("2"), rate_per_point=Decimal("0.0300")),
        DryingTier(up_to_points=Decimal("5"), rate_per_point=Decimal("0.0450")),
        DryingTier(up_to_points=None, rate_per_point=Decimal("0.0600")),
    ],
)


def test_drying_within_the_first_tier():
    assert DRYER.rate_for(Decimal("1.5")) == Decimal("0.0450")


def test_drying_spans_tiers_cumulatively():
    # 2 points at 0.03 plus 2 points at 0.045
    assert DRYER.rate_for(Decimal("4")) == Decimal("0.1500")


def test_drying_uses_the_open_tier_past_the_last_ceiling():
    # 2 at 0.03, 3 at 0.045, 1 at 0.06
    assert DRYER.rate_for(Decimal("6")) == Decimal("0.2550")


def test_free_points_come_off_the_bottom():
    schedule = DryingSchedule(
        commodity_code="CORN",
        tiers=[DryingTier(up_to_points=None, rate_per_point=Decimal("0.0500"))],
        free_points=Decimal("1.0"),
    )
    assert schedule.rate_for(Decimal("1.0")) == Decimal("0.0000")
    assert schedule.rate_for(Decimal("3.0")) == Decimal("0.1000")


def test_drying_charge_extends_over_bushels():
    assert DRYER.charge(Decimal("932.8571"), Decimal("4")) == Decimal("139.93")


def test_minimum_charge_applies_only_when_something_is_owed():
    schedule = DryingSchedule(
        commodity_code="CORN",
        tiers=[DryingTier(up_to_points=None, rate_per_point=Decimal("0.0500"))],
        minimum_charge=Decimal("25.00"),
    )
    assert schedule.charge(Decimal("100"), Decimal("0")) == Decimal("0.00")
    assert schedule.charge(Decimal("100"), Decimal("1")) == Decimal("25.00")


def test_tiers_must_be_in_order():
    with pytest.raises(ValueError):
        DryingSchedule(
            commodity_code="CORN",
            tiers=[
                DryingTier(up_to_points=Decimal("5"), rate_per_point=Decimal("0.03")),
                DryingTier(up_to_points=Decimal("2"), rate_per_point=Decimal("0.05")),
            ],
        )


def test_nothing_may_follow_the_open_tier():
    with pytest.raises(ValueError):
        DryingSchedule(
            commodity_code="CORN",
            tiers=[
                DryingTier(up_to_points=None, rate_per_point=Decimal("0.03")),
                DryingTier(up_to_points=Decimal("9"), rate_per_point=Decimal("0.05")),
            ],
        )


def test_a_schedule_needs_at_least_one_tier():
    with pytest.raises(ValueError):
        DryingSchedule(commodity_code="CORN", tiers=[])


def test_drying_basis_defaults_to_wet_bushels():
    assert DRYER.basis is DryingBasis.WET
