from datetime import UTC, datetime
from decimal import Decimal

import pytest
from sqlalchemy import inspect, select
from sqlalchemy.exc import IntegrityError

from bushelworks.persistence.base import create_all, drop_all, engine_for, session_factory
from bushelworks.persistence.models import BinReadingRow, BinRow

TAKEN = datetime(2024, 11, 27, 6, 0, tzinfo=UTC)


@pytest.fixture
def make_session():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    yield session_factory(engine)
    drop_all(engine)


def a_bin(**overrides) -> BinRow:
    base = dict(
        bin_id="B-04",
        location_code="HOME",
        capacity_bushels=Decimal("100000"),
        is_aerated=True,
    )
    base.update(overrides)
    return BinRow(**base)


def a_reading(**overrides) -> BinReadingRow:
    base = dict(
        taken_at=TAKEN,
        cable=1,
        temperature_c=Decimal("12.4"),
        moisture=Decimal("14.2"),
        fan_running=True,
    )
    base.update(overrides)
    return BinReadingRow(**base)


def test_the_bin_tables_exist(make_session):
    engine = make_session.kw["bind"]
    assert {"bin", "bin_reading"} <= set(inspect(engine).get_table_names())


def test_a_bin_round_trips(make_session):
    with make_session() as session:
        session.add(a_bin())
        session.commit()
    with make_session() as session:
        found = session.scalar(select(BinRow))
        assert found.is_empty
        assert found.headroom == Decimal("100000.0000")
        assert not found.out_of_service
        assert str(found) == "HOME/B-04"


def test_a_bin_id_is_unique_per_site(make_session):
    with make_session() as session:
        session.add(a_bin())
        session.add(a_bin(location_code="LURAY"))
        session.commit()
    with make_session() as session:
        session.add(a_bin())
        with pytest.raises(IntegrityError):
            session.commit()


def test_a_bin_with_no_capacity_is_refused(make_session):
    with make_session() as session:
        session.add(a_bin(capacity_bushels=Decimal("0")))
        with pytest.raises(IntegrityError):
            session.commit()


def test_more_grain_than_the_bin_holds_is_refused(make_session):
    with make_session() as session:
        session.add(
            a_bin(bushels=Decimal("200000"), commodity_code="CORN")
        )
        with pytest.raises(IntegrityError):
            session.commit()


def test_negative_contents_are_refused(make_session):
    with make_session() as session:
        session.add(a_bin(bushels=Decimal("-1")))
        with pytest.raises(IntegrityError):
            session.commit()


def test_a_filled_bin_reports_its_headroom(make_session):
    with make_session() as session:
        session.add(a_bin(bushels=Decimal("40000"), commodity_code="CORN"))
        session.commit()
    with make_session() as session:
        found = session.scalar(select(BinRow))
        assert not found.is_empty
        assert found.headroom == Decimal("60000.0000")


def test_a_bin_keeps_the_blend_it_holds(make_session):
    with make_session() as session:
        session.add(
            a_bin(
                bushels=Decimal("40000"),
                commodity_code="CORN",
                moisture=Decimal("14.6"),
                test_weight=Decimal("56.2"),
            )
        )
        session.commit()
    with make_session() as session:
        found = session.scalar(select(BinRow))
        assert found.moisture == Decimal("14.6000")
        assert found.test_weight == Decimal("56.2000")


def test_readings_hang_off_the_bin_in_time_order(make_session):
    with make_session() as session:
        row = a_bin()
        row.readings = [
            a_reading(taken_at=datetime(2024, 11, 28, 6, tzinfo=UTC)),
            a_reading(),
        ]
        session.add(row)
        session.commit()
    with make_session() as session:
        found = session.scalar(select(BinRow))
        assert [reading.taken_at for reading in found.readings] == sorted(
            reading.taken_at for reading in found.readings
        )


def test_a_warm_reading_is_flagged(make_session):
    with make_session() as session:
        row = a_bin()
        row.readings = [a_reading(temperature_c=Decimal("24.0"))]
        session.add(row)
        session.commit()
        assert row.readings[0].is_warm


def test_a_cool_reading_is_not_flagged(make_session):
    with make_session() as session:
        row = a_bin()
        row.readings = [a_reading()]
        session.add(row)
        session.commit()
        assert not row.readings[0].is_warm


def test_an_impossible_temperature_is_refused(make_session):
    with make_session() as session:
        row = a_bin()
        row.readings = [a_reading(temperature_c=Decimal("120"))]
        session.add(row)
        with pytest.raises(IntegrityError):
            session.commit()


def test_several_cables_can_be_read_at_once(make_session):
    with make_session() as session:
        row = a_bin()
        row.readings = [a_reading(cable=cable) for cable in (1, 2, 3)]
        session.add(row)
        session.commit()
    with make_session() as session:
        found = session.scalar(select(BinRow))
        assert {reading.cable for reading in found.readings} == {1, 2, 3}


def test_deleting_a_bin_takes_its_readings(make_session):
    with make_session() as session:
        row = a_bin()
        row.readings = [a_reading()]
        session.add(row)
        session.commit()
        session.delete(row)
        session.commit()
    with make_session() as session:
        assert session.scalars(select(BinReadingRow)).all() == []


def test_a_bin_out_of_service_keeps_the_reason(make_session):
    with make_session() as session:
        session.add(a_bin(out_of_service=True, out_of_service_reason="fan motor"))
        session.commit()
    with make_session() as session:
        found = session.scalar(select(BinRow))
        assert found.out_of_service
        assert found.out_of_service_reason == "fan motor"
