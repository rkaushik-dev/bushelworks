from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.position import (
    PositionError,
    PositionLine,
    PositionReport,
    hedge_requirement,
    reconcile,
    roll_forward,
)

AS_OF = date(2024, 11, 1)


def line(**overrides) -> PositionLine:
    base = dict(
        as_of=AS_OF,
        commodity_code="CORN",
        location_code="HOME",
        on_hand=Decimal("1000000"),
        storage_obligation=Decimal("400000"),
        deferred_price=Decimal("150000"),
        purchased_forward=Decimal("250000"),
        sold_forward=Decimal("600000"),
    )
    base.update(overrides)
    return PositionLine(**base)


def test_owned_is_on_hand_less_what_belongs_to_producers():
    assert line().owned == Decimal("450000.0000")


def test_net_long_adds_deferred_price_back_in():
    # 450000 owned + 150000 dp + 250000 bought - 600000 sold
    assert line().net_long == Decimal("250000.0000")


def test_a_house_can_be_short():
    short = line(sold_forward=Decimal("900000"))
    assert short.net_long == Decimal("-50000.0000")
    assert short.is_short


def test_in_transit_counts_toward_the_long_side():
    assert line(in_transit=Decimal("40000")).net_long == Decimal("290000.0000")


def test_obligation_is_everything_owed_to_somebody():
    assert line().obligation == Decimal("1150000.0000")


def test_a_negative_component_is_refused():
    with pytest.raises(PositionError):
        line(on_hand=Decimal("-1"))


def test_combining_two_locations_adds_the_components():
    home = line()
    luray = line(location_code="LURAY", on_hand=Decimal("500000"))
    rolled = home.combine(luray)
    assert rolled.location_code == "*"
    assert rolled.on_hand == Decimal("1500000")


def test_combining_the_same_location_keeps_the_code():
    rolled = line().combine(line())
    assert rolled.location_code == "HOME"


def test_combining_different_commodities_is_refused():
    with pytest.raises(PositionError):
        line().combine(line(commodity_code="SOYB"))


def test_combining_different_days_is_refused():
    with pytest.raises(PositionError):
        line().combine(line(as_of=date(2024, 11, 2)))


def test_a_report_rolls_up_by_commodity():
    report = PositionReport(as_of=AS_OF)
    report.add(line())
    report.add(line(location_code="LURAY", on_hand=Decimal("500000")))
    report.add(line(commodity_code="SOYB", on_hand=Decimal("200000")))
    assert report.commodities() == ("CORN", "SOYB")
    assert report.by_commodity("CORN").on_hand == Decimal("1500000")


def test_a_report_refuses_a_line_from_another_day():
    report = PositionReport(as_of=AS_OF)
    with pytest.raises(PositionError):
        report.add(line(as_of=date(2024, 11, 2)))


def test_asking_for_a_commodity_that_is_not_there():
    report = PositionReport(as_of=AS_OF)
    with pytest.raises(PositionError):
        report.by_commodity("HRW")


def test_the_report_finds_the_short_lines():
    report = PositionReport(as_of=AS_OF)
    report.add(line())
    report.add(line(commodity_code="SOYB", sold_forward=Decimal("2000000")))
    shorts = report.shorts()
    assert [s.commodity_code for s in shorts] == ["SOYB"]


def test_the_report_totals_what_the_house_owns():
    report = PositionReport(as_of=AS_OF)
    report.add(line())
    report.add(line(location_code="LURAY"))
    assert report.total_owned() == Decimal("900000.0000")


def test_the_hedge_is_rounded_toward_zero():
    assert hedge_requirement(line()) == 50
    partial = line(purchased_forward=Decimal("252400"))
    assert hedge_requirement(partial) == 50


def test_a_short_position_hedges_the_other_way():
    short = line(sold_forward=Decimal("900000"))
    assert hedge_requirement(short) == -10


def test_a_zero_contract_size_is_refused():
    with pytest.raises(PositionError):
        hedge_requirement(line(), Decimal("0"))


def test_a_small_bin_variance_is_written_off():
    assert reconcile(line(), Decimal("1002000"), Decimal("0.5")) == Decimal("2000.0000")


def test_a_wild_bin_variance_is_refused():
    with pytest.raises(PositionError):
        reconcile(line(), Decimal("1080000"), Decimal("0.5"))


def test_a_negative_tolerance_is_refused():
    with pytest.raises(PositionError):
        reconcile(line(), Decimal("1000000"), Decimal("-1"))


def test_rolling_the_position_forward_keeps_the_numbers():
    rolled = roll_forward([line()], date(2024, 11, 2))
    assert rolled[0].as_of == date(2024, 11, 2)
    assert rolled[0].net_long == line().net_long
