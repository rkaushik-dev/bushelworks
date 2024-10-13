from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.liens import (
    Interest,
    LienError,
    LienKind,
    check_balances,
    live_interests,
    notices,
    split_payment,
)

FILED = date(2024, 3, 1)
SETTLED = date(2024, 10, 20)


def joint(**overrides) -> Interest:
    base = dict(
        reference="UCC-2024-0114",
        party_code="MERRICK",
        holder="Farmers State Bank",
        kind=LienKind.JOINT_PAYEE,
        filed_on=FILED,
    )
    base.update(overrides)
    return Interest(**base)


def assignment(**overrides) -> Interest:
    base = dict(
        reference="ASG-2024-0022",
        party_code="MERRICK",
        holder="Great Plains Seed",
        kind=LienKind.ASSIGNMENT,
        filed_on=FILED,
        amount_limit=Decimal("4000.00"),
    )
    base.update(overrides)
    return Interest(**base)


def notice(**overrides) -> Interest:
    base = dict(
        reference="NOT-2024-0009",
        party_code="MERRICK",
        holder="Sunflower Credit",
        kind=LienKind.NOTICE_ONLY,
        filed_on=FILED,
    )
    base.update(overrides)
    return Interest(**base)


def test_an_interest_needs_a_reference_and_a_holder():
    with pytest.raises(LienError):
        joint(reference="")
    with pytest.raises(LienError):
        joint(holder="")


def test_an_assignment_needs_an_amount():
    with pytest.raises(LienError):
        assignment(amount_limit=None)


def test_an_assignment_of_nothing_is_refused():
    with pytest.raises(LienError):
        assignment(amount_limit=Decimal("0"))


def test_a_joint_payee_cannot_carry_an_amount():
    with pytest.raises(LienError):
        joint(amount_limit=Decimal("1000"))


def test_an_interest_released_before_filing_is_refused():
    with pytest.raises(LienError):
        joint(released_on=date(2024, 1, 1))


def test_an_interest_is_live_from_its_filing():
    assert not joint().is_live_on(date(2024, 2, 1))
    assert joint().is_live_on(SETTLED)


def test_a_released_interest_stops_biting():
    released = joint(released_on=date(2024, 9, 1))
    assert released.is_live_on(date(2024, 8, 1))
    assert not released.is_live_on(SETTLED)


def test_a_blanket_interest_covers_every_commodity():
    assert joint().covers("CORN")
    assert joint().covers("SOYB")


def test_a_specific_interest_covers_only_its_commodity():
    specific = joint(commodity_code="CORN")
    assert specific.covers("CORN")
    assert not specific.covers("SOYB")


def test_live_interests_are_oldest_filing_first():
    later = assignment(reference="ASG-9", filed_on=date(2024, 6, 1))
    found = live_interests([later, joint()], "CORN", SETTLED)
    assert [one.reference for one in found] == ["UCC-2024-0114", "ASG-9"]


def test_a_producer_with_nothing_filed_gets_one_cheque():
    payments = split_payment("MERRICK", Decimal("10000.00"), [], "CORN", SETTLED)
    assert len(payments) == 1
    assert payments[0].payee == "MERRICK"
    assert not payments[0].is_joint
    assert payments[0].dollars == Decimal("10000.00")


def test_a_joint_payee_goes_on_the_producers_cheque():
    payments = split_payment(
        "MERRICK", Decimal("10000.00"), [joint()], "CORN", SETTLED
    )
    assert len(payments) == 1
    assert payments[0].is_joint
    assert payments[0].names == ("MERRICK", "Farmers State Bank")


def test_two_joint_payees_both_go_on():
    second = joint(reference="UCC-2", holder="Sunflower Credit")
    payments = split_payment(
        "MERRICK", Decimal("10000.00"), [joint(), second], "CORN", SETTLED
    )
    assert len(payments[0].names) == 3


def test_an_assignment_comes_off_the_top():
    payments = split_payment(
        "MERRICK", Decimal("10000.00"), [assignment()], "CORN", SETTLED
    )
    assert [p.payee for p in payments] == ["Great Plains Seed", "MERRICK"]
    assert payments[0].dollars == Decimal("4000.00")
    assert payments[1].dollars == Decimal("6000.00")


def test_an_assignment_is_capped_at_what_it_is_written_for():
    payments = split_payment(
        "MERRICK", Decimal("100000.00"), [assignment()], "CORN", SETTLED
    )
    assert payments[0].dollars == Decimal("4000.00")


def test_an_assignment_bigger_than_the_settlement_takes_all_of_it():
    payments = split_payment(
        "MERRICK", Decimal("2500.00"), [assignment()], "CORN", SETTLED
    )
    assert len(payments) == 1
    assert payments[0].payee == "Great Plains Seed"
    assert payments[0].dollars == Decimal("2500.00")


def test_two_assignments_are_paid_oldest_filing_first():
    later = assignment(
        reference="ASG-9",
        holder="Cheyenne Chemical",
        filed_on=date(2024, 6, 1),
        amount_limit=Decimal("5000.00"),
    )
    payments = split_payment(
        "MERRICK", Decimal("6000.00"), [later, assignment()], "CORN", SETTLED
    )
    assert [p.payee for p in payments] == ["Great Plains Seed", "Cheyenne Chemical"]
    assert payments[0].dollars == Decimal("4000.00")
    assert payments[1].dollars == Decimal("2000.00")


def test_an_assignment_and_a_joint_payee_both_apply():
    payments = split_payment(
        "MERRICK", Decimal("10000.00"), [assignment(), joint()], "CORN", SETTLED
    )
    assert payments[0].payee == "Great Plains Seed"
    assert payments[1].names == ("MERRICK", "Farmers State Bank")


def test_a_notice_only_interest_changes_nothing():
    payments = split_payment(
        "MERRICK", Decimal("10000.00"), [notice()], "CORN", SETTLED
    )
    assert len(payments) == 1
    assert not payments[0].is_joint


def test_a_notice_still_shows_on_the_statement_footer():
    assert [one.reference for one in notices([notice(), joint()], SETTLED)] == [
        "NOT-2024-0009"
    ]


def test_an_interest_on_another_commodity_does_not_bite():
    payments = split_payment(
        "MERRICK",
        Decimal("10000.00"),
        [assignment(commodity_code="SOYB")],
        "CORN",
        SETTLED,
    )
    assert len(payments) == 1
    assert payments[0].payee == "MERRICK"


def test_a_released_interest_does_not_bite():
    payments = split_payment(
        "MERRICK",
        Decimal("10000.00"),
        [assignment(released_on=date(2024, 9, 1))],
        "CORN",
        SETTLED,
    )
    assert payments[0].payee == "MERRICK"


def test_a_settlement_of_nothing_writes_no_cheques():
    assert split_payment("MERRICK", Decimal("0"), [joint()], "CORN", SETTLED) == []
    assert split_payment("MERRICK", Decimal("-100"), [], "CORN", SETTLED) == []


def test_the_cheques_add_back_to_the_settlement():
    net = Decimal("10000.00")
    payments = split_payment("MERRICK", net, [assignment(), joint()], "CORN", SETTLED)
    check_balances(payments, net)


def test_a_short_cheque_run_is_caught():
    payments = split_payment("MERRICK", Decimal("10000.00"), [], "CORN", SETTLED)
    with pytest.raises(LienError):
        check_balances(payments, Decimal("12000.00"))


def test_no_cheques_balance_against_a_debit_settlement():
    check_balances([], Decimal("-500.00"))
