from datetime import date, datetime, timedelta
from decimal import Decimal

import pytest

from bushelworks.domain.dryer import (
    GALLONS_PER_POINT_PER_BUSHEL,
    DryerError,
    Run,
    season,
)

STARTED = datetime(2024, 10, 3, 6, 0)
RATE = Decimal("0.0450")
PROPANE = Decimal("1.85")
POWER = Decimal("0.11")


def a_run(**overrides) -> Run:
    base = dict(
        run_id="D-0114",
        location_code="HOME",
        commodity_code="CORN",
        started_at=STARTED,
        ended_at=STARTED + timedelta(hours=8),
        bushels=Decimal("20000"),
        points_removed=Decimal("3.2"),
        propane_gallons=Decimal("1280"),
        kwh=Decimal("768"),
        operator="dkoehn",
    )
    base.update(overrides)
    return Run(**base)


def test_a_run_needs_an_identifier():
    with pytest.raises(DryerError):
        a_run(run_id="")


def test_a_run_cannot_end_before_it_starts():
    with pytest.raises(DryerError):
        a_run(ended_at=STARTED - timedelta(hours=1))


def test_negative_quantities_are_refused():
    with pytest.raises(DryerError):
        a_run(propane_gallons=Decimal("-1"))


def test_the_hours_come_off_the_clock():
    assert a_run().hours == Decimal("8.00")


def test_the_point_bushels_are_what_everything_is_charged_on():
    assert a_run().point_bushels == Decimal("64000.00")


def test_the_revenue_is_the_published_rate_over_the_point_bushels():
    assert a_run().revenue(RATE) == Decimal("2880.00")


def test_the_cost_is_propane_plus_power():
    assert a_run().cost(PROPANE, POWER) == Decimal("2452.48")


def test_the_margin_is_the_difference():
    assert a_run().margin(RATE, PROPANE, POWER) == Decimal("427.52")


def test_a_run_that_dried_nothing_is_idle():
    idle = a_run(bushels=Decimal("0"), points_removed=Decimal("0"))
    assert idle.is_idle
    assert idle.revenue(RATE) == Decimal("0.00")
    assert idle.cost(PROPANE, POWER) > Decimal("0")


def test_an_idle_run_has_no_fuel_rate():
    idle = a_run(bushels=Decimal("0"), points_removed=Decimal("0"))
    assert idle.gallons_per_point_bushel() is None
    assert not idle.is_thirsty()


def test_the_fuel_rate_compares_with_the_rule_of_thumb():
    assert a_run().gallons_per_point_bushel() == Decimal("0.0200")
    assert Decimal("0.02") == GALLONS_PER_POINT_PER_BUSHEL
    assert not a_run().is_thirsty()


def test_a_thirsty_run_is_flagged():
    assert a_run(propane_gallons=Decimal("2000")).is_thirsty()


def test_a_season_gathers_the_runs_inside_it():
    spring = datetime(2025, 3, 1, 6, 0)
    runs = [
        a_run(),
        a_run(run_id="D-0115", started_at=spring, ended_at=spring + timedelta(hours=6)),
    ]
    autumn = season(
        runs, date(2024, 9, 1), date(2024, 12, 31), RATE, PROPANE, POWER
    )
    assert len(autumn.runs) == 1


def test_a_season_that_closes_first_is_refused():
    with pytest.raises(DryerError):
        season([], date(2024, 12, 31), date(2024, 9, 1), RATE, PROPANE, POWER)


def test_the_season_totals_the_bushels_and_the_hours():
    runs = [a_run(), a_run(run_id="D-0115", started_at=STARTED + timedelta(days=1),
                           ended_at=STARTED + timedelta(days=1, hours=6))]
    autumn = season(runs, date(2024, 9, 1), date(2024, 12, 31), RATE, PROPANE, POWER)
    assert autumn.bushels == Decimal("40000.0000")
    assert autumn.hours == Decimal("14.00")


def test_the_season_margin_is_revenue_less_cost():
    autumn = season([a_run()], date(2024, 9, 1), date(2024, 12, 31), RATE, PROPANE, POWER)
    assert autumn.margin == autumn.revenue - autumn.cost


def test_the_idle_runs_are_picked_out():
    idle = a_run(run_id="D-0116", bushels=Decimal("0"), points_removed=Decimal("0"))
    autumn = season(
        [a_run(), idle], date(2024, 9, 1), date(2024, 12, 31), RATE, PROPANE, POWER
    )
    assert [run.run_id for run in autumn.idle_runs] == ["D-0116"]


def test_the_thirsty_runs_are_picked_out():
    thirsty = a_run(run_id="D-0117", propane_gallons=Decimal("2000"))
    autumn = season(
        [a_run(), thirsty], date(2024, 9, 1), date(2024, 12, 31), RATE, PROPANE, POWER
    )
    assert [run.run_id for run in autumn.thirsty_runs] == ["D-0117"]


def test_the_break_even_rate_is_what_the_house_would_have_to_charge():
    autumn = season([a_run()], date(2024, 9, 1), date(2024, 12, 31), RATE, PROPANE, POWER)
    assert autumn.break_even_rate() == Decimal("0.0383")
    assert autumn.break_even_rate() < RATE


def test_a_season_that_dried_nothing_has_no_break_even():
    autumn = season([], date(2024, 9, 1), date(2024, 12, 31), RATE, PROPANE, POWER)
    assert autumn.break_even_rate() is None
    assert autumn.margin == Decimal("0.00")


def test_a_wet_year_at_a_low_rate_loses_money():
    autumn = season(
        [a_run()], date(2024, 9, 1), date(2024, 12, 31), Decimal("0.0300"), PROPANE, POWER
    )
    assert autumn.margin < Decimal("0")
