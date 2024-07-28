"""What ends up in the evidence table when the ordinary things happen."""

from dataclasses import replace
from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.contracts import ContractKind, DeliveryWindow, PurchaseContract
from bushelworks.persistence.base import create_all, drop_all, engine_for, session_factory
from bushelworks.persistence.unitofwork import unit_of_work
from bushelworks.services.audit import Action, Document, count, for_document, for_party
from bushelworks.services.scalehouse import record_load
from bushelworks.services.settlement_run import settle_producer

SETTLED = date(2024, 10, 20)
SPOT = Decimal("4.3500")


@pytest.fixture
def factory():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    yield session_factory(engine)
    drop_all(engine)


def a_contract():
    return PurchaseContract(
        number="F-2024-118",
        kind=ContractKind.FORWARD,
        party_id="MERRICK",
        commodity_code="CORN",
        location_code="HOME",
        written_on=date(2024, 5, 14),
        bushels=Decimal("20000"),
        window=DeliveryWindow(date(2024, 9, 1), date(2024, 11, 30)),
        flat_price=Decimal("4.6200"),
    )


def test_recording_a_load_leaves_one_ticket_entry(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules, actor="rmerrick")
        uow.commit()
    with unit_of_work(factory) as uow:
        entries = for_document(uow, Document.TICKET, "A-10441")
    assert len(entries) == 1
    assert entries[0].action == Action.RECORDED
    assert entries[0].actor == "rmerrick"
    assert "grade 1" in entries[0].detail


def test_an_applied_load_also_marks_the_contract(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        uow.contracts.add(a_contract())
        record_load(uow, corn_ticket, "MERRICK", rules)
        uow.commit()
    with unit_of_work(factory) as uow:
        entries = for_document(uow, Document.CONTRACT, "F-2024-118")
    assert len(entries) == 1
    assert entries[0].action == Action.APPLIED
    assert "A-10441" in entries[0].detail


def test_an_unapplied_load_leaves_the_contract_alone(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
        uow.commit()
    with unit_of_work(factory) as uow:
        assert for_document(uow, Document.CONTRACT, "F-2024-118") == []


def test_settling_leaves_an_entry_with_the_net(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
        statement = settle_producer(
            uow, "MERRICK", "HOME", rules, SETTLED, SPOT, actor="office"
        )
        uow.commit()
    with unit_of_work(factory) as uow:
        entries = for_document(uow, Document.SETTLEMENT, statement.number)
    assert len(entries) == 1
    assert entries[0].amount == statement.net
    assert entries[0].actor == "office"
    assert "A-10441" in entries[0].detail


def test_a_producers_trail_holds_both_kinds(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
        settle_producer(uow, "MERRICK", "HOME", rules, SETTLED, SPOT)
        uow.commit()
    with unit_of_work(factory) as uow:
        kinds = [entry.document_kind for entry in for_party(uow, "MERRICK")]
    assert Document.TICKET in kinds
    assert Document.SETTLEMENT in kinds


def test_two_loads_leave_two_ticket_entries(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
        record_load(
            uow, replace(corn_ticket, ticket_number="A-10442"), "MERRICK", rules
        )
        uow.commit()
    with unit_of_work(factory) as uow:
        assert count(uow) == 2


def test_a_rolled_back_load_leaves_nothing(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
    with unit_of_work(factory) as uow:
        assert count(uow) == 0


def test_a_refused_settlement_leaves_nothing(factory, rules):
    from bushelworks.services.settlement_run import NothingToSettle

    with unit_of_work(factory) as uow:
        with pytest.raises(NothingToSettle):
            settle_producer(uow, "HOLLIS", "HOME", rules, SETTLED, SPOT)
        uow.rollback()
    with unit_of_work(factory) as uow:
        assert count(uow) == 0


def test_the_entry_carries_the_business_date_not_the_clock(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
        uow.commit()
    with unit_of_work(factory) as uow:
        from sqlalchemy import select

        from bushelworks.persistence.models import AuditRow

        effective = uow.session.scalar(select(AuditRow.effective_on))
    assert effective == date(2024, 10, 3)


def test_a_split_load_records_the_applying_producer(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        uow.contracts.add(a_contract())
        record_load(uow, corn_ticket, "MERRICK", rules)
        uow.commit()
    with unit_of_work(factory) as uow:
        entry = for_document(uow, Document.CONTRACT, "F-2024-118")[0]
    assert entry.party_code == "MERRICK"
