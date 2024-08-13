from dataclasses import replace
from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.contracts import ContractKind, DeliveryWindow, PurchaseContract
from bushelworks.domain.settlement import LineKind
from bushelworks.persistence.base import create_all, drop_all, engine_for, session_factory
from bushelworks.persistence.unitofwork import unit_of_work
from bushelworks.services.charges import Advance, Deduction, StorageCharge
from bushelworks.services.scalehouse import record_load
from bushelworks.services.settlement_run import NothingToSettle, settle_producer

SETTLED = date(2024, 10, 20)
SPOT = Decimal("4.3500")


@pytest.fixture
def factory():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    yield session_factory(engine)
    drop_all(engine)


def a_contract(number="F-2024-118", bushels="20000", price="4.6200"):
    return PurchaseContract(
        number=number,
        kind=ContractKind.FORWARD,
        party_id="MERRICK",
        commodity_code="CORN",
        location_code="HOME",
        written_on=date(2024, 5, 14),
        bushels=Decimal(bushels),
        window=DeliveryWindow(date(2024, 9, 1), date(2024, 11, 30)),
        flat_price=Decimal(price),
    )


def test_one_load_settles_at_spot(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
        statement = settle_producer(uow, "MERRICK", "HOME", rules, SETTLED, SPOT)
        uow.commit()
    assert statement.number == "S-2024-0001"
    assert statement.gross == Decimal("3904.70")
    statement.check_balances()


def test_two_loads_share_one_statement(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
        record_load(
            uow, replace(corn_ticket, ticket_number="A-10442"), "MERRICK", rules
        )
        statement = settle_producer(uow, "MERRICK", "HOME", rules, SETTLED, SPOT)
        uow.commit()
    assert statement.ticket_numbers == ("A-10441", "A-10442")
    assert len(statement.of_kind(LineKind.GROSS)) == 2
    assert statement.gross == Decimal("7809.40")


def test_a_contracted_load_settles_at_the_contract_price(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        uow.contracts.add(a_contract())
        record_load(uow, corn_ticket, "MERRICK", rules)
        statement = settle_producer(uow, "MERRICK", "HOME", rules, SETTLED, SPOT)
        uow.commit()
    assert statement.gross == Decimal("4147.06")


def test_a_partly_contracted_load_blends_the_two_prices(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        uow.contracts.add(a_contract(bushels="500"))
        record_load(uow, corn_ticket, "MERRICK", rules)
        statement = settle_producer(uow, "MERRICK", "HOME", rules, SETTLED, SPOT)
        uow.commit()
    assert Decimal("3904.70") < statement.gross < Decimal("4147.06")


def test_settled_tickets_do_not_settle_twice(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
        settle_producer(uow, "MERRICK", "HOME", rules, SETTLED, SPOT)
        uow.commit()
    with unit_of_work(factory) as uow:
        with pytest.raises(NothingToSettle):
            settle_producer(uow, "MERRICK", "HOME", rules, SETTLED, SPOT)


def test_a_producer_with_nothing_waiting_is_refused(factory, rules):
    with unit_of_work(factory) as uow:
        with pytest.raises(NothingToSettle):
            settle_producer(uow, "HOLLIS", "HOME", rules, SETTLED, SPOT)


def test_only_the_named_tickets_are_settled(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
        record_load(
            uow, replace(corn_ticket, ticket_number="A-10442"), "MERRICK", rules
        )
        statement = settle_producer(
            uow,
            "MERRICK",
            "HOME",
            rules,
            SETTLED,
            SPOT,
            ticket_numbers=["A-10441"],
        )
        uow.commit()
    assert statement.ticket_numbers == ("A-10441",)
    with unit_of_work(factory) as uow:
        waiting = uow.tickets.awaiting_settlement("HOME")
        assert [row.ticket_number for row in waiting] == ["A-10442"]


def test_naming_a_ticket_that_is_not_waiting_is_refused(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
        with pytest.raises(NothingToSettle):
            settle_producer(
                uow,
                "MERRICK",
                "HOME",
                rules,
                SETTLED,
                SPOT,
                ticket_numbers=["A-99999"],
            )


def test_another_producers_tickets_are_left_alone(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
        record_load(
            uow, replace(corn_ticket, ticket_number="A-10442"), "HOLLIS", rules
        )
        statement = settle_producer(uow, "MERRICK", "HOME", rules, SETTLED, SPOT)
        uow.commit()
    assert statement.ticket_numbers == ("A-10441",)


def test_another_sites_tickets_are_left_alone(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
        record_load(
            uow,
            replace(corn_ticket, ticket_number="A-10442", location_code="LURAY"),
            "MERRICK",
            rules,
        )
        statement = settle_producer(uow, "MERRICK", "HOME", rules, SETTLED, SPOT)
        uow.commit()
    assert statement.ticket_numbers == ("A-10441",)


def test_the_checkoff_is_levied_once_across_the_statement(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
        record_load(
            uow, replace(corn_ticket, ticket_number="A-10442"), "MERRICK", rules
        )
        statement = settle_producer(uow, "MERRICK", "HOME", rules, SETTLED, SPOT)
        uow.commit()
    levies = statement.of_kind(LineKind.ASSESSMENT)
    assert len(levies) == 1
    assert levies[0].dollars == Decimal("17.95")


def test_discounts_are_itemised_per_ticket(factory, rules, corn_ticket):
    from bushelworks.domain.commodity import Factor

    dirty = replace(
        corn_ticket,
        ticket_number="A-10442",
        sample={**corn_ticket.sample, Factor.TEST_WEIGHT: Decimal("53.4")},
    )
    with unit_of_work(factory) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
        record_load(uow, dirty, "MERRICK", rules)
        statement = settle_producer(uow, "MERRICK", "HOME", rules, SETTLED, SPOT)
        uow.commit()
    codes = [line.code for line in statement.of_kind(LineKind.DISCOUNT)]
    assert codes == ["TEST_WEIGHT-A-10442"]


def test_advances_and_storage_come_through(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
        statement = settle_producer(
            uow,
            "MERRICK",
            "HOME",
            rules,
            SETTLED,
            SPOT,
            advances=[Advance("A", Decimal("1000.00"), date(2024, 9, 12))],
            storage=[StorageCharge("L-1", Decimal("42.00"), SETTLED)],
        )
        uow.commit()
    assert statement.subtotal(LineKind.ADVANCE) == Decimal("1000.00")
    assert statement.subtotal(LineKind.STORAGE) == Decimal("42.00")
    statement.check_balances()


def test_settlement_numbers_run_consecutively(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
        record_load(
            uow, replace(corn_ticket, ticket_number="A-10442"), "HOLLIS", rules
        )
        first = settle_producer(uow, "MERRICK", "HOME", rules, SETTLED, SPOT)
        second = settle_producer(uow, "HOLLIS", "HOME", rules, SETTLED, SPOT)
        uow.commit()
    assert first.number == "S-2024-0001"
    assert second.number == "S-2024-0002"


def test_an_other_deduction_lands_on_its_own_line(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
        statement = settle_producer(
            uow,
            "MERRICK",
            "HOME",
            rules,
            SETTLED,
            SPOT,
            deductions=[
                Deduction("FREIGHT", "freight to Salina", Decimal("184.50"))
            ],
        )
        uow.commit()
    lines = statement.of_kind(LineKind.DEDUCTION)
    assert [line.code for line in lines] == ["FREIGHT"]
    assert lines[0].dollars == Decimal("184.50")
    statement.check_balances()


def test_a_deduction_of_nothing_is_refused():
    with pytest.raises(Exception):
        Deduction("FREIGHT", "freight", Decimal("0"))


def test_a_deduction_needs_a_description():
    with pytest.raises(Exception):
        Deduction("FREIGHT", "", Decimal("10.00"))


def test_deductions_come_before_advances_on_the_statement(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
        statement = settle_producer(
            uow,
            "MERRICK",
            "HOME",
            rules,
            SETTLED,
            SPOT,
            advances=[Advance("A", Decimal("100.00"), date(2024, 9, 12))],
            deductions=[Deduction("FREIGHT", "freight", Decimal("184.50"))],
        )
        uow.commit()
    kinds = [line.kind for line in statement.lines]
    assert kinds.index(LineKind.ADVANCE) < kinds.index(LineKind.DEDUCTION)


def test_the_statement_is_stored_and_reads_back(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
        statement = settle_producer(uow, "MERRICK", "HOME", rules, SETTLED, SPOT)
        uow.commit()
    with unit_of_work(factory) as uow:
        stored = uow.settlements.get(statement.number)
    assert stored.net == statement.net
