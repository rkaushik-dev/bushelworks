from datetime import date
from decimal import Decimal

import pytest
from sqlalchemy import inspect, select
from sqlalchemy.exc import IntegrityError

from bushelworks.persistence.base import create_all, drop_all, engine_for, session_factory
from bushelworks.persistence.models import (
    ApplicationRow,
    ContractRow,
    ScaleTicketRow,
    SettlementLineRow,
    SettlementRow,
    SettlementTicketRow,
)


@pytest.fixture
def make_session():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    yield session_factory(engine)
    drop_all(engine)


def a_contract(**overrides) -> ContractRow:
    base = dict(
        number="F-2024-118",
        kind="forward",
        party_code="MERRICK",
        commodity_code="CORN",
        location_code="HOME",
        written_on=date(2024, 5, 14),
        bushels=Decimal("20000"),
        window_opens=date(2024, 10, 1),
        window_closes=date(2024, 11, 30),
        flat_price=Decimal("4.6200"),
    )
    base.update(overrides)
    return ContractRow(**base)


def a_ticket(**overrides) -> ScaleTicketRow:
    base = dict(
        ticket_number="A-10441",
        location_code="HOME",
        scale_date=date(2024, 10, 3),
        commodity_code="CORN",
        party_code="MERRICK",
        gross_pounds=Decimal("78420"),
        tare_pounds=Decimal("26180"),
        disposition="contract",
    )
    base.update(overrides)
    return ScaleTicketRow(**base)


def test_the_trading_tables_exist(make_session):
    engine = make_session.kw["bind"]
    names = set(inspect(engine).get_table_names())
    assert {
        "purchase_contract",
        "contract_application",
        "settlement",
        "settlement_line",
        "settlement_ticket",
    } <= names


def test_a_contract_round_trips(make_session):
    with make_session() as session:
        session.add(a_contract())
        session.commit()
    with make_session() as session:
        found = session.scalar(
            select(ContractRow).where(ContractRow.number == "F-2024-118")
        )
        assert found.status == "open"
        assert found.tolerance_percent == Decimal("2.0000")
        assert str(found).startswith("F-2024-118 CORN")


def test_a_contract_number_is_unique_across_the_house(make_session):
    with make_session() as session:
        session.add(a_contract())
        session.commit()
    with make_session() as session:
        session.add(a_contract(location_code="LURAY"))
        with pytest.raises(IntegrityError):
            session.commit()


def test_a_contract_of_no_bushels_is_refused(make_session):
    with make_session() as session:
        session.add(a_contract(bushels=Decimal("0")))
        with pytest.raises(IntegrityError):
            session.commit()


def test_a_window_that_closes_first_is_refused(make_session):
    with make_session() as session:
        session.add(
            a_contract(window_opens=date(2024, 11, 30), window_closes=date(2024, 10, 1))
        )
        with pytest.raises(IntegrityError):
            session.commit()


def test_an_application_links_a_ticket_to_a_contract(make_session):
    with make_session() as session:
        ticket = a_ticket()
        contract = a_contract()
        session.add_all([ticket, contract])
        session.flush()
        session.add(
            ApplicationRow(
                ticket_id=ticket.id,
                contract_id=contract.id,
                bushels=Decimal("932.8571"),
                applied_on=date(2024, 10, 3),
            )
        )
        session.commit()

    with make_session() as session:
        contract = session.scalar(
            select(ContractRow).where(ContractRow.number == "F-2024-118")
        )
        assert len(contract.applications) == 1
        assert contract.applications[0].bushels == Decimal("932.8571")


def test_one_ticket_lands_on_one_contract_only_once(make_session):
    with make_session() as session:
        ticket = a_ticket()
        contract = a_contract()
        session.add_all([ticket, contract])
        session.flush()
        for _ in range(2):
            session.add(
                ApplicationRow(
                    ticket_id=ticket.id,
                    contract_id=contract.id,
                    bushels=Decimal("100"),
                    applied_on=date(2024, 10, 3),
                )
            )
        with pytest.raises(IntegrityError):
            session.commit()


def test_an_application_needs_a_real_contract(make_session):
    with make_session() as session:
        ticket = a_ticket()
        session.add(ticket)
        session.flush()
        session.add(
            ApplicationRow(
                ticket_id=ticket.id,
                contract_id=9999,
                bushels=Decimal("100"),
                applied_on=date(2024, 10, 3),
            )
        )
        with pytest.raises(IntegrityError):
            session.commit()


def test_a_settlement_keeps_its_lines_in_sequence(make_session):
    with make_session() as session:
        settlement = SettlementRow(
            number="S-2024-0417",
            settled_on=date(2024, 10, 20),
            party_code="MERRICK",
            location_code="HOME",
            gross=Decimal("3904.70"),
            deductions=Decimal("111.31"),
            net=Decimal("3793.39"),
        )
        settlement.lines = [
            SettlementLineRow(
                sequence=2,
                kind="drying",
                code="DRY",
                description="drying",
                dollars=Decimal("102.33"),
            ),
            SettlementLineRow(
                sequence=1,
                kind="gross",
                code="GRAIN",
                description="corn",
                dollars=Decimal("3904.70"),
            ),
        ]
        session.add(settlement)
        session.commit()

    with make_session() as session:
        found = session.scalar(
            select(SettlementRow).where(SettlementRow.number == "S-2024-0417")
        )
        assert [line.sequence for line in found.lines] == [1, 2]


def test_a_settlement_line_may_not_be_stored_negative(make_session):
    with make_session() as session:
        settlement = SettlementRow(
            number="S-1",
            settled_on=date(2024, 10, 20),
            party_code="MERRICK",
            location_code="HOME",
            gross=Decimal("100"),
            deductions=Decimal("0"),
            net=Decimal("100"),
        )
        settlement.lines = [
            SettlementLineRow(
                sequence=1,
                kind="discount",
                code="TW",
                description="nope",
                dollars=Decimal("-1.00"),
            )
        ]
        session.add(settlement)
        with pytest.raises(IntegrityError):
            session.commit()


def test_deleting_a_settlement_takes_its_lines(make_session):
    with make_session() as session:
        settlement = SettlementRow(
            number="S-1",
            settled_on=date(2024, 10, 20),
            party_code="MERRICK",
            location_code="HOME",
            gross=Decimal("100"),
            deductions=Decimal("0"),
            net=Decimal("100"),
        )
        settlement.lines = [
            SettlementLineRow(
                sequence=1,
                kind="gross",
                code="GRAIN",
                description="corn",
                dollars=Decimal("100.00"),
            )
        ]
        session.add(settlement)
        session.commit()
        session.delete(settlement)
        session.commit()
    with make_session() as session:
        assert session.scalars(select(SettlementLineRow)).all() == []


def test_a_settlement_can_name_the_tickets_it_covers(make_session):
    with make_session() as session:
        ticket = a_ticket()
        settlement = SettlementRow(
            number="S-1",
            settled_on=date(2024, 10, 20),
            party_code="MERRICK",
            location_code="HOME",
            gross=Decimal("100"),
            deductions=Decimal("0"),
            net=Decimal("100"),
        )
        session.add_all([ticket, settlement])
        session.flush()
        session.add(
            SettlementTicketRow(settlement_id=settlement.id, ticket_id=ticket.id)
        )
        session.commit()
    with make_session() as session:
        assert len(session.scalars(select(SettlementTicketRow)).all()) == 1
