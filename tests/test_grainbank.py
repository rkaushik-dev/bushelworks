from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.grainbank import (
    MILL_SHRINK_PERCENT,
    FeedAccount,
    GrainBankError,
    Ration,
    standard_rations,
)
from bushelworks.errors import ConflictError

OPENED = date(2024, 10, 3)
RATIONS = standard_rations()
CRACK = RATIONS["CRACK"]
FINISH = RATIONS["FIN"]


def an_account(**overrides) -> FeedAccount:
    base = dict(
        party_code="STROH",
        commodity_code="CORN",
        location_code="HOME",
        opened_on=OPENED,
    )
    base.update(overrides)
    return FeedAccount(**base)


def test_the_mill_runs_three_rations():
    assert sorted(RATIONS) == ["CRACK", "FIN", "GROW"]


def test_an_inclusion_out_of_range_is_refused():
    with pytest.raises(GrainBankError):
        Ration("X", "X", Decimal("0"), Decimal("10.00"))
    with pytest.raises(GrainBankError):
        Ration("X", "X", Decimal("120"), Decimal("10.00"))


def test_negative_processing_is_refused():
    with pytest.raises(GrainBankError):
        Ration("X", "X", Decimal("80"), Decimal("-1"))


def test_cracked_corn_takes_a_ton_of_corn_plus_the_mill_shrink():
    # 2000 lb of corn plus 0.5 percent, over 56 lb a bushel
    assert CRACK.bushels_for(Decimal("1")) == Decimal("35.8929")
    assert MILL_SHRINK_PERCENT == Decimal("0.5")


def test_a_leaner_ration_takes_fewer_bushels():
    assert FINISH.bushels_for(Decimal("1")) < CRACK.bushels_for(Decimal("1"))


def test_making_no_feed_is_refused():
    with pytest.raises(GrainBankError):
        CRACK.bushels_for(Decimal("0"))


def test_processing_is_charged_by_the_ton():
    assert FINISH.processing_charge(Decimal("12")) == Decimal("150.00")


def test_a_new_account_is_empty():
    account = an_account()
    assert account.balance == Decimal("0.0000")
    assert not account.is_converted
    assert account.charges == Decimal("0.00")


def test_a_deposit_puts_bushels_in():
    account = an_account().deposit("A-10441", Decimal("932.8571"), OPENED)
    assert account.balance == Decimal("932.8571")
    assert account.movements[0].is_deposit


def test_a_deposit_of_nothing_is_refused():
    with pytest.raises(GrainBankError):
        an_account().deposit("A-1", Decimal("0"), OPENED)


def test_a_withdrawal_takes_the_rations_bushels_out():
    account = an_account().deposit("A-1", Decimal("1000"), OPENED)
    after = account.withdraw("F-1", CRACK, Decimal("10"), date(2024, 10, 10))
    assert after.balance == Decimal("1000.0000") - CRACK.bushels_for(Decimal("10"))


def test_a_withdrawal_records_the_tons_and_the_ration():
    account = an_account().deposit("A-1", Decimal("1000"), OPENED)
    after = account.withdraw("F-1", FINISH, Decimal("12"), date(2024, 10, 10))
    movement = after.movements[-1]
    assert movement.tons == Decimal("12")
    assert movement.ration_code == "FIN"
    assert not movement.is_deposit


def test_a_withdrawal_carries_the_processing_charge():
    account = an_account().deposit("A-1", Decimal("1000"), OPENED)
    after = account.withdraw("F-1", FINISH, Decimal("12"), date(2024, 10, 10))
    assert after.charges == Decimal("150.00")


def test_withdrawing_more_than_is_in_the_account_is_refused():
    account = an_account().deposit("A-1", Decimal("100"), OPENED)
    with pytest.raises(GrainBankError):
        account.withdraw("F-1", CRACK, Decimal("10"), date(2024, 10, 10))


def test_the_tons_taken_add_up():
    account = (
        an_account()
        .deposit("A-1", Decimal("2000"), OPENED)
        .withdraw("F-1", CRACK, Decimal("10"), date(2024, 10, 10))
        .withdraw("F-2", FINISH, Decimal("12"), date(2024, 10, 24))
    )
    assert account.tons_taken() == Decimal("22.000")


def test_activity_since_a_date_leaves_out_the_earlier_movements():
    account = (
        an_account()
        .deposit("A-1", Decimal("2000"), OPENED)
        .withdraw("F-1", CRACK, Decimal("10"), date(2024, 10, 10))
        .withdraw("F-2", CRACK, Decimal("5"), date(2024, 11, 4))
    )
    assert [m.reference for m in account.activity(date(2024, 11, 1))] == ["F-2"]


def test_converting_closes_the_account_to_feed():
    account = an_account().deposit("A-1", Decimal("1000"), OPENED)
    converted = account.convert_to_storage(date(2024, 12, 1))
    assert converted.is_converted
    assert converted.converted_on == date(2024, 12, 1)


def test_a_converted_account_takes_no_more_grain():
    converted = (
        an_account().deposit("A-1", Decimal("1000"), OPENED)
        .convert_to_storage(date(2024, 12, 1))
    )
    with pytest.raises(ConflictError):
        converted.deposit("A-2", Decimal("100"), date(2024, 12, 2))


def test_a_converted_account_gives_out_no_more_feed():
    converted = (
        an_account().deposit("A-1", Decimal("1000"), OPENED)
        .convert_to_storage(date(2024, 12, 1))
    )
    with pytest.raises(ConflictError):
        converted.withdraw("F-1", CRACK, Decimal("1"), date(2024, 12, 2))


def test_converting_twice_is_a_conflict():
    converted = (
        an_account().deposit("A-1", Decimal("1000"), OPENED)
        .convert_to_storage(date(2024, 12, 1))
    )
    with pytest.raises(ConflictError):
        converted.convert_to_storage(date(2024, 12, 2))


def test_an_empty_account_cannot_be_converted():
    with pytest.raises(GrainBankError):
        an_account().convert_to_storage(date(2024, 12, 1))


def test_the_balance_survives_a_run_of_movements():
    account = an_account().deposit("A-1", Decimal("5000"), OPENED)
    for index in range(4):
        account = account.withdraw(
            f"F-{index}", CRACK, Decimal("10"), date(2024, 10, 10 + index)
        )
    expected = Decimal("5000.0000") - CRACK.bushels_for(Decimal("10")) * 4
    assert account.balance == expected
