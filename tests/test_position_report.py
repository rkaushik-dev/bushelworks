from datetime import date
from decimal import Decimal

from bushelworks.domain.position import PositionLine, PositionReport
from bushelworks.reports.position import (
    WIDTH,
    bushels,
    compare,
    hedge_sheet,
    render,
    to_csv,
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


def a_report() -> PositionReport:
    report = PositionReport(as_of=AS_OF)
    report.add(line())
    report.add(
        line(
            commodity_code="SOYB",
            on_hand=Decimal("900000"),
            storage_obligation=Decimal("200000"),
            deferred_price=Decimal("50000"),
            purchased_forward=Decimal("100000"),
            sold_forward=Decimal("300000"),
        )
    )
    return report


def test_bushels_are_printed_whole_with_separators():
    assert bushels(Decimal("1000000")) == "1,000,000"
    assert bushels(Decimal("932.8571")) == "933"


def test_a_short_prints_in_brackets():
    assert bushels(Decimal("-50000")) == "(50,000)"


def test_the_board_fits_its_width():
    text = render(a_report())
    assert all(len(row) <= WIDTH for row in text.split("\n"))


def test_the_board_names_the_day_and_the_house():
    text = render(a_report(), house_name="LURAY GRAIN CO")
    assert "LURAY GRAIN CO" in text
    assert "POSITION AS OF 2024-11-01" in text


def test_every_commodity_gets_a_row():
    text = render(a_report())
    assert "CORN" in text
    assert "SOYB" in text


def test_the_board_totals_what_the_house_owns():
    text = render(a_report())
    assert "TOTAL OWNED" in text


def test_a_short_is_called_out_at_the_bottom():
    report = PositionReport(as_of=AS_OF)
    report.add(line(sold_forward=Decimal("2000000")))
    text = render(report)
    assert "SHORT:" in text
    assert "is short" in text


def test_a_flat_board_says_nothing_about_shorts():
    assert "SHORT:" not in render(a_report())


def test_the_hedge_sheet_rounds_toward_zero():
    text = hedge_sheet(a_report())
    assert "HEDGE SHEET" in text
    assert "CORN" in text
    assert "CONTRACTS" in text


def test_the_hedge_sheet_shows_the_residual():
    report = PositionReport(as_of=AS_OF)
    report.add(line(purchased_forward=Decimal("252400")))
    text = hedge_sheet(report)
    assert "2,400" in text


def test_a_different_contract_size_changes_the_lots():
    report = PositionReport(as_of=AS_OF)
    report.add(line())
    five = hedge_sheet(report, Decimal("5000"))
    one = hedge_sheet(report, Decimal("1000"))
    assert five != one


def test_the_csv_has_a_header_and_a_row_per_line():
    text = to_csv(a_report())
    rows = text.strip().split("\n")
    assert rows[0].startswith("as_of,commodity_code,location_code")
    assert len(rows) == 3


def test_the_csv_keeps_the_unrounded_quantities():
    report = PositionReport(as_of=AS_OF)
    report.add(line(on_hand=Decimal("932.8571")))
    assert "932.8571" in to_csv(report)


def test_the_csv_carries_the_derived_columns():
    text = to_csv(a_report())
    assert "owned" in text.split("\n")[0]
    assert "net_long" in text.split("\n")[0]


def test_comparing_two_days_shows_the_move():
    yesterday = [line()]
    today = [line(purchased_forward=Decimal("300000"))]
    moves = dict(compare(yesterday, today))
    assert moves["CORN"] == Decimal("50000")


def test_a_commodity_that_appeared_overnight_shows_its_whole_position():
    moves = dict(compare([], [line()]))
    assert moves["CORN"] == line().net_long


def test_a_commodity_that_went_away_shows_the_other_way():
    moves = dict(compare([line()], []))
    assert moves["CORN"] == -line().net_long


def test_comparing_two_identical_days_shows_nothing_moved():
    moves = dict(compare([line()], [line()]))
    assert moves["CORN"] == Decimal("0")
