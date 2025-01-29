from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.contracts import ContractKind, DeliveryWindow, PurchaseContract
from bushelworks.reports.fulfilment import (
    FulfilmentError,
    State,
    at_risk,
    line_for,
    outstanding_total,
    render,
    short,
    to_csv,
)

AS_OF = date(2024, 10, 20)


def a_contract(number="F-2024-118", bushels="20000", closes=date(2024, 11, 30)):
    return PurchaseContract(
        number=number,
        kind=ContractKind.FORWARD,
        party_id="MERRICK",
        commodity_code="CORN",
        location_code="HOME",
        written_on=date(2024, 5, 14),
        bushels=Decimal(bushels),
        window=DeliveryWindow(date(2024, 9, 1), closes),
        flat_price=Decimal("4.6200"),
    )


def a_line(applied="0", weekly="0", **kw):
    return line_for(a_contract(**kw), Decimal(applied), AS_OF, Decimal(weekly))


def test_a_negative_applied_is_refused():
    with pytest.raises(FulfilmentError):
        line_for(a_contract(), Decimal("-1"), AS_OF)


def test_the_outstanding_is_measured_to_the_tolerance_floor():
    line = a_line(applied="10000")
    assert line.outstanding == Decimal("9600.0000")


def test_a_contract_inside_tolerance_is_filled():
    line = a_line(applied="19600")
    assert line.outstanding == Decimal("0.0000")
    assert line.state is State.FILLED
    assert not line.needs_a_call


def test_an_overfilled_contract_is_still_filled():
    assert a_line(applied="21000").state is State.FILLED


def test_the_weeks_left_come_off_the_calendar():
    line = a_line()
    assert line.weeks_left > Decimal("5")
    assert line.weeks_left < Decimal("7")


def test_a_closed_window_has_no_weeks_left():
    line = a_line(closes=date(2024, 10, 1))
    assert line.weeks_left == Decimal("0")


def test_a_producer_hauling_fast_enough_is_on_track():
    line = a_line(applied="10000", weekly="2000")
    assert line.deliverable > line.outstanding
    assert line.state is State.ON_TRACK
    assert not line.needs_a_call


def test_a_producer_hauling_too_slowly_is_at_risk():
    line = a_line(applied="10000", weekly="200")
    assert line.state is State.AT_RISK
    assert line.needs_a_call


def test_a_producer_who_has_not_started_is_at_risk():
    assert a_line(applied="0").state is State.AT_RISK


def test_a_contract_past_its_window_with_bushels_out_is_short():
    line = a_line(applied="10000", closes=date(2024, 10, 1))
    assert line.state is State.SHORT
    assert line.needs_a_call


def test_a_contract_past_its_window_and_filled_is_not_short():
    line = a_line(applied="19600", closes=date(2024, 10, 1))
    assert line.state is State.FILLED


def test_the_sheet_lists_every_contract():
    lines = [a_line(applied="19600"), a_line(number="F-2024-119", applied="0")]
    text = render(lines, AS_OF)
    assert "F-2024-118" in text
    assert "F-2024-119" in text
    assert "2 contracts" in text


def test_the_sheet_fits_its_width():
    text = render([a_line()], AS_OF)
    assert all(len(line) <= 90 for line in text.split("\n"))


def test_the_sheet_counts_the_calls_to_make():
    lines = [a_line(applied="19600"), a_line(number="F-2024-119", applied="0")]
    text = render(lines, AS_OF)
    assert "1 need a call" in text
    assert "RING TODAY: F-2024-119" in text


def test_a_book_that_is_all_filled_rings_nobody():
    text = render([a_line(applied="19600")], AS_OF)
    assert "0 need a call" in text
    assert "RING TODAY" not in text


def test_the_csv_has_a_row_per_contract():
    lines = [a_line(), a_line(number="F-2024-119")]
    text = to_csv(lines)
    rows = text.strip().split("\n")
    assert rows[0].startswith("number,party_code")
    assert len(rows) == 3


def test_the_csv_carries_the_state():
    assert "at_risk" in to_csv([a_line()])


def test_the_at_risk_list_is_the_ones_to_ring():
    lines = [a_line(applied="19600"), a_line(number="F-2024-119")]
    assert [line.number for line in at_risk(lines)] == ["F-2024-119"]


def test_the_short_list_is_the_defaults():
    lines = [a_line(), a_line(number="F-2024-119", closes=date(2024, 10, 1))]
    assert [line.number for line in short(lines)] == ["F-2024-119"]


def test_the_outstanding_total_adds_the_book_up():
    lines = [a_line(applied="10000"), a_line(number="F-2024-119", applied="10000")]
    assert outstanding_total(lines) == Decimal("19200.0000")


def test_a_filled_book_has_nothing_outstanding():
    assert outstanding_total([a_line(applied="19600")]) == Decimal("0.0000")
