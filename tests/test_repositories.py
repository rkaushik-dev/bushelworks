from dataclasses import replace
from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.contracts import ContractKind, DeliveryWindow, PurchaseContract
from bushelworks.errors import ConflictError
from bushelworks.persistence.base import create_all, drop_all, engine_for, session_factory
from bushelworks.persistence.models import ApplicationRow, Location, Party
from bushelworks.persistence.repositories import (
    ContractNotFound,
    ContractRepository,
    PartyNotFound,
    PartyRepository,
    SettlementNotFound,
    SettlementRepository,
    TicketNotFound,
    TicketRepository,
)
from bushelworks.services.intake import receive
from bushelworks.services.settling import settle_load


@pytest.fixture
def session():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    factory = session_factory(engine)
    with factory() as s:
        yield s
    drop_all(engine)


def a_contract(number="F-2024-118", **kw) -> PurchaseContract:
    base = dict(
        number=number,
        kind=ContractKind.FORWARD,
        party_id="MERRICK",
        commodity_code="CORN",
        location_code="HOME",
        written_on=date(2024, 5, 14),
        bushels=Decimal("20000"),
        window=DeliveryWindow(date(2024, 10, 1), date(2024, 11, 30)),
        flat_price=Decimal("4.6200"),
    )
    base.update(kw)
    return PurchaseContract(**base)


def test_a_ticket_can_be_written_and_read_back(session, corn_ticket):
    repo = TicketRepository(session)
    repo.add(corn_ticket, "MERRICK")
    session.commit()
    restored = repo.get("HOME", "A-10441")
    assert restored.net_pounds == Decimal("52240")


def test_writing_the_same_ticket_number_twice_is_a_conflict(session, corn_ticket):
    repo = TicketRepository(session)
    repo.add(corn_ticket, "MERRICK")
    session.commit()
    with pytest.raises(ConflictError):
        repo.add(corn_ticket, "MERRICK")


def test_the_same_number_at_another_site_is_fine(session, corn_ticket):
    repo = TicketRepository(session)
    repo.add(corn_ticket, "MERRICK")
    repo.add(replace(corn_ticket, location_code="LURAY"), "MERRICK")
    session.commit()
    assert repo.exists("LURAY", "A-10441")


def test_a_missing_ticket_says_so(session):
    with pytest.raises(TicketNotFound):
        TicketRepository(session).get("HOME", "A-99999")


def test_voiding_marks_the_row_and_keeps_the_weights(session, corn_ticket):
    repo = TicketRepository(session)
    repo.add(corn_ticket, "MERRICK")
    session.commit()
    row = repo.void("HOME", "A-10441", "reweighed")
    assert row.voided
    assert row.remarks == "VOID: reweighed"
    assert row.net_pounds == Decimal("52240")


def test_a_ticket_cannot_be_voided_twice(session, corn_ticket):
    repo = TicketRepository(session)
    repo.add(corn_ticket, "MERRICK")
    repo.void("HOME", "A-10441", "reweighed")
    with pytest.raises(ConflictError):
        repo.void("HOME", "A-10441", "again")


def test_a_settled_ticket_cannot_be_voided(session, corn_ticket):
    repo = TicketRepository(session)
    row = repo.add(corn_ticket, "MERRICK")
    row.settled = True
    session.flush()
    with pytest.raises(ConflictError):
        repo.void("HOME", "A-10441", "too late")


def test_tickets_awaiting_settlement_skip_voids(session, corn_ticket):
    repo = TicketRepository(session)
    repo.add(corn_ticket, "MERRICK")
    repo.add(replace(corn_ticket, ticket_number="A-10442"), "MERRICK")
    repo.void("HOME", "A-10442", "reweighed")
    session.commit()
    waiting = repo.awaiting_settlement()
    assert [row.ticket_number for row in waiting] == ["A-10441"]


def test_tickets_for_a_party_can_be_limited_by_date(session, corn_ticket):
    repo = TicketRepository(session)
    repo.add(corn_ticket, "MERRICK")
    repo.add(
        replace(corn_ticket, ticket_number="A-10500", scale_date=date(2024, 11, 2)),
        "MERRICK",
    )
    session.commit()
    assert len(repo.for_party("MERRICK")) == 2
    assert len(repo.for_party("MERRICK", since=date(2024, 11, 1))) == 1


def test_net_pounds_received_excludes_voids(session, corn_ticket):
    repo = TicketRepository(session)
    repo.add(corn_ticket, "MERRICK")
    repo.add(replace(corn_ticket, ticket_number="A-10442"), "MERRICK")
    repo.void("HOME", "A-10442", "reweighed")
    session.commit()
    assert repo.bushels_received("CORN", date(2024, 10, 1), date(2024, 10, 31)) == (
        Decimal("52240")
    )


def test_a_contract_can_be_written_and_read_back(session):
    repo = ContractRepository(session)
    repo.add(a_contract())
    session.commit()
    assert repo.get("F-2024-118").unit_price == Decimal("4.6200")


def test_a_duplicate_contract_number_is_a_conflict(session):
    repo = ContractRepository(session)
    repo.add(a_contract())
    session.commit()
    with pytest.raises(ConflictError):
        repo.add(a_contract())


def test_a_missing_contract_says_so(session):
    with pytest.raises(ContractNotFound):
        ContractRepository(session).get("F-9999")


def test_an_amended_contract_replaces_its_row(session):
    repo = ContractRepository(session)
    repo.add(a_contract())
    session.commit()
    amended = replace(repo.get("F-2024-118"), tolerance_percent=Decimal("5"))
    repo.replace(amended)
    session.commit()
    assert repo.get("F-2024-118").tolerance_percent == Decimal("5.0000")


def test_open_contracts_are_filtered_by_window(session):
    repo = ContractRepository(session)
    repo.add(a_contract())
    repo.add(
        a_contract(
            number="F-2024-119",
            window=DeliveryWindow(date(2025, 1, 1), date(2025, 3, 31)),
        )
    )
    session.commit()
    found = repo.open_for("MERRICK", "CORN", date(2024, 10, 15))
    assert [c.number for c in found] == ["F-2024-118"]


def test_applied_bushels_ignores_reversed_applications(session, corn_ticket):
    tickets = TicketRepository(session)
    contracts = ContractRepository(session)
    ticket_row = tickets.add(corn_ticket, "MERRICK")
    contract_row = contracts.add(a_contract())
    session.add(
        ApplicationRow(
            ticket_id=ticket_row.id,
            contract_id=contract_row.id,
            bushels=Decimal("900"),
            applied_on=date(2024, 10, 3),
            reversed_on=date(2024, 10, 4),
        )
    )
    session.commit()
    assert contracts.applied_bushels("F-2024-118") == Decimal("0")


def test_closing_a_contract_twice_is_a_conflict(session):
    repo = ContractRepository(session)
    repo.add(a_contract())
    repo.close("F-2024-118")
    with pytest.raises(ConflictError):
        repo.close("F-2024-118")


def test_a_settlement_can_be_written_and_read_back(session, rules, corn_ticket):
    load = receive(corn_ticket, rules)
    statement = settle_load(
        "S-2024-0417", load, Decimal("4.35"), rules, "MERRICK", date(2024, 10, 20)
    )
    repo = SettlementRepository(session)
    repo.add(statement)
    session.commit()
    assert repo.get("S-2024-0417").net == statement.net


def test_a_settlement_can_name_its_tickets(session, rules, corn_ticket):
    tickets = TicketRepository(session)
    row = tickets.add(corn_ticket, "MERRICK")
    load = receive(corn_ticket, rules)
    statement = settle_load(
        "S-1", load, Decimal("4.35"), rules, "MERRICK", date(2024, 10, 20)
    )
    SettlementRepository(session).add(statement, ticket_ids=[row.id])
    session.commit()


def test_a_missing_settlement_says_so(session):
    with pytest.raises(SettlementNotFound):
        SettlementRepository(session).get("S-9999")


def test_paying_a_settlement_twice_is_a_conflict(session, rules, corn_ticket):
    load = receive(corn_ticket, rules)
    statement = settle_load(
        "S-1", load, Decimal("4.35"), rules, "MERRICK", date(2024, 10, 20)
    )
    repo = SettlementRepository(session)
    repo.add(statement)
    repo.mark_paid("S-1", "10442", date(2024, 10, 21))
    session.commit()
    with pytest.raises(ConflictError):
        repo.mark_paid("S-1", "10443", date(2024, 10, 22))


def test_unpaid_settlements_are_listed_oldest_first(session, rules, corn_ticket):
    load = receive(corn_ticket, rules)
    repo = SettlementRepository(session)
    repo.add(
        settle_load("S-2", load, Decimal("4.35"), rules, "MERRICK", date(2024, 10, 21))
    )
    repo.add(
        settle_load("S-1", load, Decimal("4.35"), rules, "MERRICK", date(2024, 10, 20))
    )
    session.commit()
    assert [row.number for row in repo.unpaid()] == ["S-1", "S-2"]


def test_settlements_for_a_day(session, rules, corn_ticket):
    load = receive(corn_ticket, rules)
    repo = SettlementRepository(session)
    repo.add(
        settle_load("S-1", load, Decimal("4.35"), rules, "MERRICK", date(2024, 10, 20))
    )
    session.commit()
    assert len(repo.for_day(date(2024, 10, 20))) == 1
    assert repo.for_day(date(2024, 10, 21)) == []


def test_the_party_repository_finds_active_parties(session):
    session.add_all(
        [
            Party(code="MERRICK", name="Merrick Farms", sort_name="MERRICK"),
            Party(code="HOLLIS", name="Hollis", sort_name="HOLLIS", is_active=False),
            Location(code="HOME", name="Home", state_code="KS"),
        ]
    )
    session.commit()
    repo = PartyRepository(session)
    assert [p.code for p in repo.active()] == ["MERRICK"]
    assert repo.get("HOLLIS").name == "Hollis"
    assert [loc.code for loc in repo.locations()] == ["HOME"]
    with pytest.raises(PartyNotFound):
        repo.get("NOBODY")
