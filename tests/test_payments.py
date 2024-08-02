from dataclasses import replace
from datetime import date
from decimal import Decimal

import pytest

from bushelworks.persistence.base import create_all, drop_all, engine_for, session_factory
from bushelworks.persistence.unitofwork import unit_of_work
from bushelworks.services.audit import Document, for_document
from bushelworks.services.payments import (
    PaymentError,
    debit_balances,
    next_cheque_number,
    reconcile,
    run_cheques,
    unpaid_by_party,
    void_cheque,
)
from bushelworks.services.scalehouse import record_load
from bushelworks.services.settlement_run import settle_producer

SETTLED = date(2024, 10, 20)
PAID = date(2024, 10, 24)
SPOT = Decimal("4.3500")


@pytest.fixture
def factory():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    yield session_factory(engine)
    drop_all(engine)


@pytest.fixture
def settled(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
        record_load(
            uow,
            replace(corn_ticket, ticket_number="A-10442"),
            "HOLLIS",
            rules,
        )
        settle_producer(uow, "MERRICK", "HOME", rules, SETTLED, SPOT)
        settle_producer(uow, "HOLLIS", "HOME", rules, SETTLED, SPOT)
        uow.commit()
    return factory


def test_cheque_numbers_keep_their_leading_zeroes():
    assert next_cheque_number("010441", 1) == "010442"
    assert next_cheque_number("010441", 0) == "010441"


def test_a_cheque_number_that_would_grow_past_the_stock_is_refused():
    with pytest.raises(PaymentError):
        next_cheque_number("9999", 1)


def test_a_cheque_number_has_to_be_digits():
    with pytest.raises(PaymentError):
        next_cheque_number("A-1", 1)


def test_the_unpaid_pile_groups_by_producer(settled):
    with unit_of_work(settled) as uow:
        grouped = unpaid_by_party(uow)
    assert sorted(grouped) == ["HOLLIS", "MERRICK"]


def test_a_run_writes_one_cheque_per_producer(settled):
    with unit_of_work(settled) as uow:
        run = run_cheques(uow, PAID, "010441")
        uow.commit()
    assert run.count == 2
    assert [cheque.cheque_number for cheque in run.cheques] == ["010441", "010442"]


def test_the_cheques_come_out_in_producer_order(settled):
    with unit_of_work(settled) as uow:
        run = run_cheques(uow, PAID, "010441")
        uow.commit()
    assert [cheque.party_code for cheque in run.cheques] == ["HOLLIS", "MERRICK"]


def test_a_paid_settlement_drops_off_the_pile(settled):
    with unit_of_work(settled) as uow:
        run_cheques(uow, PAID, "010441")
        uow.commit()
    with unit_of_work(settled) as uow:
        assert unpaid_by_party(uow) == {}


def test_running_twice_finds_nothing_the_second_time(settled):
    with unit_of_work(settled) as uow:
        run_cheques(uow, PAID, "010441")
        uow.commit()
    with unit_of_work(settled) as uow:
        with pytest.raises(PaymentError):
            run_cheques(uow, PAID, "010443")


def test_two_settlements_for_one_producer_share_a_cheque(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
        settle_producer(uow, "MERRICK", "HOME", rules, SETTLED, SPOT)
        record_load(
            uow, replace(corn_ticket, ticket_number="A-10442"), "MERRICK", rules
        )
        settle_producer(uow, "MERRICK", "HOME", rules, date(2024, 10, 21), SPOT)
        uow.commit()
    with unit_of_work(factory) as uow:
        run = run_cheques(uow, PAID, "010441")
        uow.commit()
    assert run.count == 1
    assert run.cheques[0].is_multiple
    assert len(run.cheques[0].settlement_numbers) == 2


def test_a_run_can_be_limited_to_named_producers(settled):
    with unit_of_work(settled) as uow:
        run = run_cheques(uow, PAID, "010441", party_codes=["MERRICK"])
        uow.commit()
    assert run.count == 1
    assert run.cheques[0].party_code == "MERRICK"
    with unit_of_work(settled) as uow:
        assert sorted(unpaid_by_party(uow)) == ["HOLLIS"]


def test_naming_a_producer_with_nothing_unpaid_is_refused(settled):
    with unit_of_work(settled) as uow:
        with pytest.raises(PaymentError):
            run_cheques(uow, PAID, "010441", party_codes=["BROCKMAN"])


def test_a_run_with_nothing_to_pay_is_refused(factory):
    with unit_of_work(factory) as uow:
        with pytest.raises(PaymentError):
            run_cheques(uow, PAID, "010441")


def test_the_run_totals_what_it_wrote(settled):
    with unit_of_work(settled) as uow:
        run = run_cheques(uow, PAID, "010441")
        uow.commit()
    assert run.total == sum(cheque.dollars for cheque in run.cheques)


def test_a_cheque_leaves_an_audit_entry(settled):
    with unit_of_work(settled) as uow:
        run_cheques(uow, PAID, "010441", actor="bookkeeper")
        uow.commit()
    with unit_of_work(settled) as uow:
        entries = for_document(uow, Document.PAYMENT, "010441")
    assert len(entries) == 1
    assert entries[0].actor == "bookkeeper"


def test_voiding_a_cheque_puts_the_settlements_back(settled):
    with unit_of_work(settled) as uow:
        run_cheques(uow, PAID, "010441")
        uow.commit()
    with unit_of_work(settled) as uow:
        void_cheque(uow, "010441", "printer chewed it")
        uow.commit()
    with unit_of_work(settled) as uow:
        assert len(unpaid_by_party(uow)) == 1


def test_voiding_a_cheque_nobody_wrote_is_refused(settled):
    with unit_of_work(settled) as uow:
        with pytest.raises(PaymentError):
            void_cheque(uow, "099999", "wrong number")


def test_a_void_needs_a_reason(settled):
    with unit_of_work(settled) as uow:
        run_cheques(uow, PAID, "010441")
        with pytest.raises(PaymentError):
            void_cheque(uow, "010441", "")


def test_a_producer_owing_the_house_is_held_rather_than_paid(settled, rules):
    with unit_of_work(settled) as uow:
        row = uow.settlements.row("S-2024-0001")
        row.net = Decimal("-250.00")
        uow.commit()
    with unit_of_work(settled) as uow:
        run = run_cheques(uow, PAID, "010441")
        uow.commit()
    assert [party for party, _ in run.held] == ["MERRICK"]
    assert run.count == 1


def test_debit_balances_are_reported_separately(settled):
    with unit_of_work(settled) as uow:
        row = uow.settlements.row("S-2024-0001")
        row.net = Decimal("-250.00")
        uow.commit()
    with unit_of_work(settled) as uow:
        assert debit_balances(uow) == [("MERRICK", Decimal("-250.00"))]


def test_reconciling_a_matching_bank_list_finds_nothing(settled):
    with unit_of_work(settled) as uow:
        run = run_cheques(uow, PAID, "010441")
        uow.commit()
    cleared = {cheque.cheque_number: cheque.dollars for cheque in run.cheques}
    with unit_of_work(settled) as uow:
        assert reconcile(uow, cleared) == []


def test_reconciling_finds_a_cheque_cleared_for_the_wrong_amount(settled):
    with unit_of_work(settled) as uow:
        run = run_cheques(uow, PAID, "010441")
        uow.commit()
    cleared = {cheque.cheque_number: cheque.dollars for cheque in run.cheques}
    first = run.cheques[0].cheque_number
    cleared[first] = cleared[first] + Decimal("1.00")
    with unit_of_work(settled) as uow:
        assert reconcile(uow, cleared) == [first]


def test_reconciling_finds_a_cheque_the_house_never_wrote(settled):
    with unit_of_work(settled) as uow:
        run_cheques(uow, PAID, "010441")
        uow.commit()
    with unit_of_work(settled) as uow:
        assert reconcile(uow, {"099999": Decimal("100.00")}) == ["099999"]
