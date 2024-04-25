from datetime import date
from decimal import Decimal

import pytest
from sqlalchemy import inspect, select
from sqlalchemy.exc import IntegrityError

from bushelworks.persistence.base import (
    as_decimal,
    create_all,
    drop_all,
    engine_for,
    session_factory,
    sessions,
)
from bushelworks.persistence.models import CommodityRow, Location, Party


@pytest.fixture
def engine():
    eng = engine_for("sqlite+pysqlite:///:memory:")
    create_all(eng)
    yield eng
    drop_all(eng)


@pytest.fixture
def make_session(engine):
    return session_factory(engine)


def test_the_schema_has_the_reference_tables(engine):
    names = set(inspect(engine).get_table_names())
    assert {"party", "location", "commodity", "split_agreement"} <= names


def test_constraints_are_named_by_the_convention(engine):
    uniques = inspect(engine).get_unique_constraints("party")
    assert any(u["name"] == "uq_party_code" for u in uniques)


def test_a_party_round_trips(make_session):
    with make_session() as session:
        session.add(
            Party(
                code="MERRICK",
                name="Merrick Farms LLC",
                sort_name="MERRICK FARMS",
                state_code="KS",
                opened_on=date(2014, 3, 1),
            )
        )
        session.commit()

    with make_session() as session:
        found = session.scalar(select(Party).where(Party.code == "MERRICK"))
        assert found is not None
        assert found.name == "Merrick Farms LLC"
        assert found.is_active
        assert not found.checkoff_exempt
        assert found.created_at is not None


def test_a_duplicate_party_code_is_refused(make_session):
    with make_session() as session:
        session.add(Party(code="MERRICK", name="A", sort_name="A"))
        session.commit()
    with make_session() as session:
        session.add(Party(code="MERRICK", name="B", sort_name="B"))
        with pytest.raises(IntegrityError):
            session.commit()


def test_a_blank_party_code_is_refused(make_session):
    with make_session() as session:
        session.add(Party(code="", name="A", sort_name="A"))
        with pytest.raises(IntegrityError):
            session.commit()


def test_a_location_round_trips(make_session):
    with make_session() as session:
        session.add(
            Location(
                code="HOME",
                name="Home elevator",
                state_code="KS",
                warehouse_licence="KS-1174",
                licensed_capacity=1_450_000,
            )
        )
        session.commit()
    with make_session() as session:
        found = session.scalar(select(Location).where(Location.code == "HOME"))
        assert found.licensed_capacity == 1_450_000
        assert str(found) == "HOME Home elevator"


def test_a_commodity_with_a_zero_test_weight_is_refused(make_session):
    with make_session() as session:
        session.add(
            CommodityRow(
                code="X",
                name="X",
                standard_test_weight=Decimal("0"),
                base_moisture=Decimal("15"),
            )
        )
        with pytest.raises(IntegrityError):
            session.commit()


def test_a_commodity_with_an_impossible_base_moisture_is_refused(make_session):
    with make_session() as session:
        session.add(
            CommodityRow(
                code="X",
                name="X",
                standard_test_weight=Decimal("56"),
                base_moisture=Decimal("150"),
            )
        )
        with pytest.raises(IntegrityError):
            session.commit()


def test_numeric_columns_come_back_as_decimals(make_session):
    with make_session() as session:
        session.add(
            CommodityRow(
                code="CORN",
                name="Yellow Corn",
                standard_test_weight=Decimal("56"),
                base_moisture=Decimal("15.0"),
            )
        )
        session.commit()
    with make_session() as session:
        found = session.scalar(select(CommodityRow).where(CommodityRow.code == "CORN"))
        assert as_decimal(found.standard_test_weight) == Decimal("56")


def test_as_decimal_copes_with_floats_and_none():
    assert as_decimal(56.0) == Decimal("56.0")
    assert as_decimal(None) == Decimal("0")
    assert as_decimal(Decimal("4.35")) == Decimal("4.35")


def test_the_repr_names_the_primary_key(make_session):
    with make_session() as session:
        party = Party(code="MERRICK", name="A", sort_name="A")
        session.add(party)
        session.commit()
        assert repr(party).startswith("<Party id=")


def test_the_session_dependency_closes_what_it_opens(make_session):
    generator = sessions(make_session)
    session = next(generator)
    assert session.is_active
    generator.close()


def test_an_in_memory_engine_shares_one_database_across_sessions(engine):
    factory = session_factory(engine)
    with factory() as session:
        session.add(Party(code="HOLLIS", name="Hollis", sort_name="HOLLIS"))
        session.commit()
    with factory() as session:
        assert session.scalar(select(Party).where(Party.code == "HOLLIS")) is not None
