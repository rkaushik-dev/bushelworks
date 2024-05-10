from datetime import date
from decimal import Decimal

import pytest
from sqlalchemy import select

from bushelworks.domain.commodity import Factor
from bushelworks.domain.contracts import ContractKind, DeliveryWindow, PurchaseContract
from bushelworks.domain.pricing import FuturesMonth
from bushelworks.domain.settlement import LineKind
from bushelworks.domain.tickets import Disposition, SplitRole, SplitShare
from bushelworks.persistence.base import create_all, drop_all, engine_for, session_factory
from bushelworks.persistence.mappers import (
    MappingError,
    contract_from_row,
    contract_to_row,
    futures_month_from_symbol,
    settlement_from_row,
    settlement_to_row,
    ticket_from_row,
    ticket_to_row,
)
from bushelworks.persistence.models import ContractRow, ScaleTicketRow, SettlementRow
from bushelworks.services.intake import receive
from bushelworks.services.settling import settle_load


@pytest.fixture
def make_session():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    yield session_factory(engine)
    drop_all(engine)


def test_a_ticket_survives_a_round_trip_through_the_database(make_session, corn_ticket):
    with make_session() as session:
        session.add(ticket_to_row(corn_ticket, "MERRICK"))
        session.commit()

    with make_session() as session:
        row = session.scalar(
            select(ScaleTicketRow).where(ScaleTicketRow.ticket_number == "A-10441")
        )
        restored = ticket_from_row(row)

    assert restored.net_pounds == corn_ticket.net_pounds
    assert restored.sample == corn_ticket.sample
    assert restored.disposition is Disposition.CASH_SALE
    assert restored.parties() == ("MERRICK",)
    assert restored.driver == "R. Merrick"


def test_a_split_ticket_keeps_its_roles(make_session, corn_ticket):
    from dataclasses import replace

    shared = replace(
        corn_ticket,
        splits=[
            SplitShare("BROCKMAN", Decimal("25"), SplitRole.LANDLORD),
            SplitShare("MERRICK", Decimal("75"), SplitRole.TENANT),
        ],
    )
    with make_session() as session:
        session.add(ticket_to_row(shared, "MERRICK"))
        session.commit()
    with make_session() as session:
        row = session.scalar(select(ScaleTicketRow))
        restored = ticket_from_row(row)
    roles = {s.party_id: s.role for s in restored.splits}
    assert roles["BROCKMAN"] is SplitRole.LANDLORD
    assert roles["MERRICK"] is SplitRole.TENANT


def test_an_unknown_factor_in_the_database_is_refused(make_session, corn_ticket):
    from bushelworks.persistence.models import SampleRow

    with make_session() as session:
        row = ticket_to_row(corn_ticket, "MERRICK")
        row.samples.append(SampleRow(factor="colour", value=Decimal("1")))
        session.add(row)
        session.commit()
    with make_session() as session:
        stored = session.scalar(select(ScaleTicketRow))
        with pytest.raises(MappingError):
            ticket_from_row(stored)


def test_a_futures_symbol_reads_back_as_a_month():
    month = futures_month_from_symbol("CORNZ24", "CORN")
    assert month == FuturesMonth("CORN", "Z", 2024)
    assert futures_month_from_symbol(None, "CORN") is None


def test_a_symbol_for_the_wrong_commodity_is_refused():
    with pytest.raises(MappingError):
        futures_month_from_symbol("SOYBX24", "CORN")


def test_a_malformed_symbol_is_refused():
    with pytest.raises(MappingError):
        futures_month_from_symbol("CORNZZZ", "CORN")
    with pytest.raises(MappingError):
        futures_month_from_symbol("CORNB24", "CORN")


def test_a_contract_survives_a_round_trip(make_session):
    contract = PurchaseContract(
        number="H-2024-042",
        kind=ContractKind.HTA,
        party_id="MERRICK",
        commodity_code="CORN",
        location_code="HOME",
        written_on=date(2024, 5, 14),
        bushels=Decimal("10000"),
        window=DeliveryWindow(date(2024, 10, 1), date(2024, 11, 30)),
        futures_month=FuturesMonth("CORN", "Z", 2024),
        futures_price=Decimal("4.8500"),
    )
    with make_session() as session:
        session.add(contract_to_row(contract))
        session.commit()
    with make_session() as session:
        row = session.scalar(select(ContractRow))
        restored = contract_from_row(row)
    assert restored == contract
    assert not restored.is_fully_priced


def test_a_priced_forward_survives_a_round_trip(make_session):
    contract = PurchaseContract(
        number="F-2024-118",
        kind=ContractKind.FORWARD,
        party_id="MERRICK",
        commodity_code="CORN",
        location_code="HOME",
        written_on=date(2024, 5, 14),
        bushels=Decimal("20000"),
        window=DeliveryWindow(date(2024, 10, 1), date(2024, 11, 30)),
        flat_price=Decimal("4.6200"),
    )
    with make_session() as session:
        session.add(contract_to_row(contract))
        session.commit()
    with make_session() as session:
        restored = contract_from_row(session.scalar(select(ContractRow)))
    assert restored.unit_price == Decimal("4.6200")


def test_a_settlement_survives_a_round_trip(make_session, rules, corn_ticket):
    load = receive(corn_ticket, rules)
    original = settle_load(
        "S-2024-0417", load, Decimal("4.3500"), rules, "MERRICK", date(2024, 10, 20)
    )
    with make_session() as session:
        session.add(settlement_to_row(original))
        session.commit()
    with make_session() as session:
        row = session.scalar(select(SettlementRow))
        restored = settlement_from_row(row)

    assert restored.net == original.net
    assert restored.gross == original.gross
    assert [line.kind for line in restored.lines] == [
        line.kind for line in original.lines
    ]
    restored.check_balances()


def test_the_stored_totals_match_the_recomputed_ones(make_session, rules, corn_ticket):
    load = receive(corn_ticket, rules)
    original = settle_load(
        "S-1", load, Decimal("4.3500"), rules, "MERRICK", date(2024, 10, 20)
    )
    with make_session() as session:
        session.add(settlement_to_row(original))
        session.commit()
    with make_session() as session:
        row = session.scalar(select(SettlementRow))
        assert row.net == original.net
        assert row.gross == original.gross
        assert row.deductions == original.deductions


def test_line_sequence_is_written_in_canonical_order(make_session, rules, corn_ticket):
    load = receive(corn_ticket, rules)
    original = settle_load(
        "S-1", load, Decimal("4.3500"), rules, "MERRICK", date(2024, 10, 20)
    )
    with make_session() as session:
        session.add(settlement_to_row(original))
        session.commit()
    with make_session() as session:
        row = session.scalar(select(SettlementRow))
        assert [line.sequence for line in row.lines] == list(
            range(1, len(row.lines) + 1)
        )
        assert row.lines[0].kind == LineKind.GROSS.value


def test_a_ticket_with_no_optional_fields_maps_to_nulls(make_session, corn_ticket):
    from dataclasses import replace

    plain = replace(corn_ticket, truck_id="", driver="", farm="", field_name="")
    row = ticket_to_row(plain, "MERRICK")
    assert row.truck_id is None
    assert row.driver is None
    assert ticket_from_row(row).driver == ""


def test_a_sample_is_stored_in_a_stable_order(corn_ticket):
    row = ticket_to_row(corn_ticket, "MERRICK")
    assert [s.factor for s in row.samples] == sorted(s.factor for s in row.samples)
    assert Factor.MOISTURE.value in [s.factor for s in row.samples]
