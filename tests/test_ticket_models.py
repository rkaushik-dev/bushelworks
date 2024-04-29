from datetime import date
from decimal import Decimal

import pytest
from sqlalchemy import inspect, select
from sqlalchemy.exc import IntegrityError

from bushelworks.persistence.base import create_all, drop_all, engine_for, session_factory
from bushelworks.persistence.models import SampleRow, ScaleTicketRow, TicketSplitRow


@pytest.fixture
def make_session():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    yield session_factory(engine)
    drop_all(engine)


def a_ticket(**overrides) -> ScaleTicketRow:
    base = dict(
        ticket_number="A-10441",
        location_code="HOME",
        scale_date=date(2024, 10, 3),
        commodity_code="CORN",
        party_code="MERRICK",
        gross_pounds=Decimal("78420"),
        tare_pounds=Decimal("26180"),
        disposition="cash_sale",
    )
    base.update(overrides)
    return ScaleTicketRow(**base)


def test_the_ticket_tables_exist(make_session):
    engine = make_session.kw["bind"]
    names = set(inspect(engine).get_table_names())
    assert {"scale_ticket", "ticket_sample", "ticket_split"} <= names


def test_a_ticket_round_trips_with_its_children(make_session):
    with make_session() as session:
        ticket = a_ticket()
        ticket.samples = [
            SampleRow(factor="moisture", value=Decimal("18.2"), instrument="GAC2500"),
            SampleRow(factor="test_weight", value=Decimal("56.5")),
        ]
        ticket.splits = [TicketSplitRow(party_code="MERRICK", percent=Decimal("100"))]
        session.add(ticket)
        session.commit()

    with make_session() as session:
        found = session.scalar(
            select(ScaleTicketRow).where(ScaleTicketRow.ticket_number == "A-10441")
        )
        assert found.net_pounds == Decimal("52240")
        assert {s.factor for s in found.samples} == {"moisture", "test_weight"}
        assert found.splits[0].percent == Decimal("100.0000")
        assert str(found) == "HOME/A-10441"


def test_a_ticket_number_is_unique_per_location_not_globally(make_session):
    with make_session() as session:
        session.add(a_ticket())
        session.add(a_ticket(location_code="LURAY"))
        session.commit()
    with make_session() as session:
        session.add(a_ticket())
        with pytest.raises(IntegrityError):
            session.commit()


def test_a_tare_above_gross_is_refused_by_the_database(make_session):
    with make_session() as session:
        session.add(a_ticket(gross_pounds=Decimal("26180"), tare_pounds=Decimal("78420")))
        with pytest.raises(IntegrityError):
            session.commit()


def test_dockage_outside_the_range_is_refused(make_session):
    with make_session() as session:
        session.add(a_ticket(dockage_percent=Decimal("100")))
        with pytest.raises(IntegrityError):
            session.commit()


def test_one_reading_per_factor_per_ticket(make_session):
    with make_session() as session:
        ticket = a_ticket()
        ticket.samples = [
            SampleRow(factor="moisture", value=Decimal("18.2")),
            SampleRow(factor="moisture", value=Decimal("18.4")),
        ]
        session.add(ticket)
        with pytest.raises(IntegrityError):
            session.commit()


def test_a_negative_reading_is_refused(make_session):
    with make_session() as session:
        ticket = a_ticket()
        ticket.samples = [SampleRow(factor="moisture", value=Decimal("-1"))]
        session.add(ticket)
        with pytest.raises(IntegrityError):
            session.commit()


def test_a_share_over_one_hundred_is_refused(make_session):
    with make_session() as session:
        ticket = a_ticket()
        ticket.splits = [TicketSplitRow(party_code="MERRICK", percent=Decimal("101"))]
        session.add(ticket)
        with pytest.raises(IntegrityError):
            session.commit()


def test_deleting_a_ticket_takes_its_children_with_it(make_session):
    with make_session() as session:
        ticket = a_ticket()
        ticket.samples = [SampleRow(factor="moisture", value=Decimal("18.2"))]
        ticket.splits = [TicketSplitRow(party_code="MERRICK", percent=Decimal("100"))]
        session.add(ticket)
        session.commit()
        session.delete(ticket)
        session.commit()
    with make_session() as session:
        assert session.scalars(select(SampleRow)).all() == []
        assert session.scalars(select(TicketSplitRow)).all() == []


def test_a_ticket_starts_unvoided_and_unsettled(make_session):
    with make_session() as session:
        ticket = a_ticket()
        session.add(ticket)
        session.commit()
        assert not ticket.voided
        assert not ticket.settled


def test_the_models_package_still_exports_the_reference_tables():
    from bushelworks.persistence.models import CommodityRow, Location, Party

    assert Party.__tablename__ == "party"
    assert Location.__tablename__ == "location"
    assert CommodityRow.__tablename__ == "commodity"
