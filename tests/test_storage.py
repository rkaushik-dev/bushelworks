from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.storage import (
    AccrualMethod,
    StorageError,
    StorageLot,
    StorageRate,
    draw_down,
)

DAILY = StorageRate(
    commodity_code="CORN",
    rate_per_bushel_per_month=Decimal("0.0450"),
    method=AccrualMethod.DAILY,
    free_days=30,
    in_charge_per_bushel=Decimal("0.0900"),
)

ANNIVERSARY = StorageRate(
    commodity_code="CORN",
    rate_per_bushel_per_month=Decimal("0.0450"),
    method=AccrualMethod.ANNIVERSARY,
    free_days=30,
)

CALENDAR = StorageRate(
    commodity_code="CORN",
    rate_per_bushel_per_month=Decimal("0.0450"),
    method=AccrualMethod.CALENDAR,
    free_days=0,
)

RECEIVED = date(2024, 10, 15)


def test_nothing_accrues_inside_the_free_period():
    assert DAILY.chargeable_days(RECEIVED, date(2024, 11, 14)) == 0
    assert DAILY.charge(Decimal("10000"), RECEIVED, date(2024, 11, 14)) == Decimal("0.00")


def test_the_free_period_comes_off_the_front():
    assert DAILY.chargeable_days(RECEIVED, date(2024, 12, 14)) == 30


def test_daily_accrual_prorates_the_monthly_rate():
    charge = DAILY.charge(Decimal("10000"), RECEIVED, date(2024, 12, 14))
    assert charge == Decimal("450.00")


def test_daily_accrual_charges_a_part_month():
    charge = DAILY.charge(Decimal("10000"), RECEIVED, date(2024, 11, 29))
    assert charge == Decimal("225.00")


def test_anniversary_accrual_rounds_a_part_month_up():
    assert ANNIVERSARY.chargeable_months(RECEIVED, date(2024, 11, 29)) == Decimal("1")
    assert ANNIVERSARY.chargeable_months(RECEIVED, date(2024, 12, 15)) == Decimal("2")


def test_one_day_past_the_anniversary_costs_a_whole_month():
    thirty = ANNIVERSARY.charge(Decimal("10000"), RECEIVED, date(2024, 12, 14))
    thirty_one = ANNIVERSARY.charge(Decimal("10000"), RECEIVED, date(2024, 12, 15))
    assert thirty == Decimal("450.00")
    assert thirty_one == Decimal("900.00")


def test_calendar_accrual_charges_every_month_touched():
    assert CALENDAR.chargeable_months(date(2024, 10, 31), date(2024, 11, 1)) == Decimal("2")


def test_calendar_accrual_charges_one_month_for_a_single_day():
    assert CALENDAR.chargeable_months(date(2024, 10, 1), date(2024, 10, 2)) == Decimal("1")


def test_storage_cannot_be_charged_backwards():
    with pytest.raises(StorageError):
        DAILY.chargeable_days(RECEIVED, date(2024, 10, 1))


def test_minimum_charge_applies_only_once_something_is_owed():
    rate = StorageRate(
        commodity_code="CORN",
        rate_per_bushel_per_month=Decimal("0.0450"),
        minimum_charge=Decimal("35.00"),
    )
    assert rate.charge(Decimal("10"), date(2024, 10, 15), date(2024, 10, 15)) == Decimal(
        "0.00"
    )
    assert rate.charge(Decimal("10"), date(2024, 10, 15), date(2024, 11, 15)) == Decimal(
        "35.00"
    )


def test_the_in_charge_is_a_flat_per_bushel_fee():
    assert DAILY.in_charge(Decimal("10000")) == Decimal("900.00")


def test_a_negative_rate_is_refused():
    with pytest.raises(StorageError):
        StorageRate(commodity_code="CORN", rate_per_bushel_per_month=Decimal("-0.01"))


def lot(lot_id, bushels, stored_on) -> StorageLot:
    return StorageLot(
        lot_id=lot_id,
        party_id="MERRICK",
        commodity_code="CORN",
        location_code="HOME",
        bushels=Decimal(bushels),
        stored_on=stored_on,
    )


def test_a_lot_tracks_what_is_left_open():
    small = lot("L-1", "5000", RECEIVED).withdraw(Decimal("1200"))
    assert small.open_bushels == Decimal("3800.0000")
    assert not small.is_empty


def test_withdrawing_the_whole_lot_empties_it():
    empty = lot("L-1", "5000", RECEIVED).withdraw(Decimal("5000"))
    assert empty.is_empty
    assert empty.open_bushels == Decimal("0.0000")


def test_cannot_withdraw_more_than_is_open():
    with pytest.raises(StorageError):
        lot("L-1", "5000", RECEIVED).withdraw(Decimal("5001"))


def test_cannot_withdraw_nothing():
    with pytest.raises(StorageError):
        lot("L-1", "5000", RECEIVED).withdraw(Decimal("0"))


def test_a_lot_accrues_on_what_is_still_open():
    partial = lot("L-1", "10000", RECEIVED).withdraw(Decimal("5000"))
    assert partial.accrued(DAILY, date(2024, 12, 14)) == Decimal("225.00")


def test_draw_down_takes_the_oldest_lot_first():
    lots = [
        lot("L-1", "3000", date(2024, 9, 1)),
        lot("L-2", "4000", date(2024, 10, 1)),
    ]
    updated, taken = draw_down(lots, Decimal("3500"))
    assert taken == [("L-1", Decimal("3000.0000")), ("L-2", Decimal("500.0000"))]
    assert updated[0].is_empty
    assert updated[1].open_bushels == Decimal("3500.0000")


def test_draw_down_can_be_asked_for_newest_first():
    lots = [
        lot("L-1", "3000", date(2024, 9, 1)),
        lot("L-2", "4000", date(2024, 10, 1)),
    ]
    _, taken = draw_down(lots, Decimal("1000"), oldest_first=False)
    assert taken == [("L-2", Decimal("1000.0000"))]


def test_draw_down_refuses_to_overdraw():
    lots = [lot("L-1", "3000", date(2024, 9, 1))]
    with pytest.raises(StorageError):
        draw_down(lots, Decimal("3001"))


def test_draw_down_of_nothing_is_an_error():
    with pytest.raises(StorageError):
        draw_down([lot("L-1", "3000", date(2024, 9, 1))], Decimal("0"))


def test_a_lot_needs_bushels():
    with pytest.raises(StorageError):
        lot("L-1", "0", RECEIVED)
