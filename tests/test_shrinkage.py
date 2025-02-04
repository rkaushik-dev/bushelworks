from datetime import date
from decimal import Decimal

import pytest

from bushelworks.reports.shrinkage import (
    NOTABLE,
    Period,
    ShrinkageError,
    against_the_house,
    render,
    to_csv,
    total_gain,
)

OPENS = date(2024, 9, 1)
CLOSES = date(2024, 11, 30)


def a_period(**overrides) -> Period:
    base = dict(
        commodity_code="CORN",
        location_code="HOME",
        opens=OPENS,
        closes=CLOSES,
        wet_bushels_in=Decimal("1000000"),
        shrink_charged=Decimal("30000"),
        measured_loss=Decimal("26000"),
        dried_bushels=Decimal("600000"),
    )
    base.update(overrides)
    return Period(**base)


def test_a_period_that_closes_first_is_refused():
    with pytest.raises(ShrinkageError):
        a_period(opens=CLOSES, closes=OPENS)


def test_negative_bushels_are_refused():
    with pytest.raises(ShrinkageError):
        a_period(wet_bushels_in=Decimal("-1"))


def test_the_gain_is_charged_less_measured():
    assert a_period().gain == Decimal("4000.0000")


def test_a_house_losing_shrink_shows_a_negative_gain():
    period = a_period(measured_loss=Decimal("34000"))
    assert period.gain == Decimal("-4000.0000")
    assert period.direction == "against the house"


def test_a_flat_period_says_so():
    assert a_period(measured_loss=Decimal("30000")).direction == "flat"


def test_the_percentages_are_of_the_wet_bushels():
    period = a_period()
    assert period.charged_percent == Decimal("3.0000")
    assert period.measured_percent == Decimal("2.6000")


def test_a_period_with_nothing_in_it_has_no_percentages():
    period = a_period(
        wet_bushels_in=Decimal("0"),
        shrink_charged=Decimal("0"),
        measured_loss=Decimal("0"),
    )
    assert period.charged_percent == Decimal("0.0000")
    assert not period.is_notable


def test_a_small_gain_is_not_notable():
    period = a_period(measured_loss=Decimal("29900"))
    assert not period.is_notable


def test_a_large_gain_is_notable():
    assert a_period().is_notable
    assert NOTABLE == Decimal("0.002")


def test_a_large_loss_is_notable_too():
    assert a_period(measured_loss=Decimal("35000")).is_notable


def test_the_percentage_dried_is_reported():
    assert a_period().percent_dried == Decimal("60.0")


def test_the_sheet_lists_the_lines():
    text = render([a_period(), a_period(commodity_code="SOYB")])
    assert "CORN" in text
    assert "SOYB" in text
    assert "2 lines" in text


def test_the_sheet_fits_its_width():
    text = render([a_period()])
    assert all(len(line) <= 80 for line in text.split("\n"))


def test_the_sheet_explains_the_notable_lines():
    text = render([a_period()])
    assert "1 worth explaining" in text
    assert "in the house's favour" in text


def test_a_quiet_period_needs_no_explanation():
    text = render([a_period(measured_loss=Decimal("29900"))])
    assert "0 worth explaining" in text


def test_the_csv_has_a_row_per_line():
    text = to_csv([a_period(), a_period(commodity_code="SOYB")])
    rows = text.strip().split("\n")
    assert rows[0].startswith("commodity_code,location_code")
    assert len(rows) == 3


def test_the_csv_says_which_lines_are_notable():
    assert ",yes" in to_csv([a_period()])


def test_the_total_gain_adds_the_lines():
    periods = [a_period(), a_period(commodity_code="SOYB")]
    assert total_gain(periods) == Decimal("8000.0000")


def test_the_lines_against_the_house_are_picked_out():
    losing = a_period(commodity_code="SOYB", measured_loss=Decimal("34000"))
    found = against_the_house([a_period(), losing])
    assert [period.commodity_code for period in found] == ["SOYB"]


def test_a_book_all_in_the_houses_favour_has_none_against():
    assert against_the_house([a_period()]) == []
