from datetime import date
from decimal import Decimal

import pytest
from sqlalchemy import inspect, select
from sqlalchemy.exc import IntegrityError

from bushelworks.persistence.base import create_all, drop_all, engine_for, session_factory
from bushelworks.persistence.models import SaleRow, ShipmentRow


@pytest.fixture
def make_session():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    yield session_factory(engine)
    drop_all(engine)


def a_sale(**overrides) -> SaleRow:
    base = dict(
        number="S-2024-0088",
        kind="forward",
        buyer="Salina Terminal",
        destination="terminal",
        commodity_code="CORN",
        origin_code="HOME",
        written_on=date(2024, 9, 5),
        bushels=Decimal("50000"),
        window_opens=date(2024, 11, 1),
        window_closes=date(2024, 12, 31),
        flat_price=Decimal("4.7800"),
        freight_per_bushel=Decimal("0.1800"),
        spec_max_moisture=Decimal("15.0"),
        spec_min_test_weight=Decimal("54.0"),
        spec_max_damage=Decimal("5.0"),
        spec_max_foreign=Decimal("3.0"),
    )
    base.update(overrides)
    return SaleRow(**base)


def a_shipment(**overrides) -> ShipmentRow:
    base = dict(
        reference="CAR-4417",
        shipped_on=date(2024, 11, 12),
        carrier_id="BNSF 480221",
        origin_pounds=Decimal("196000"),
        origin_bushels=Decimal("3500.0000"),
    )
    base.update(overrides)
    return ShipmentRow(**base)


def test_the_sale_tables_exist(make_session):
    engine = make_session.kw["bind"]
    assert {"sale_contract", "shipment"} <= set(inspect(engine).get_table_names())


def test_a_sale_round_trips(make_session):
    with make_session() as session:
        session.add(a_sale())
        session.commit()
    with make_session() as session:
        found = session.scalar(select(SaleRow))
        assert found.status == "open"
        assert found.carrier == "truck"
        assert found.shipped == Decimal("0.0000")
        assert str(found).startswith("S-2024-0088 Salina Terminal")


def test_a_sale_number_is_unique(make_session):
    with make_session() as session:
        session.add(a_sale())
        session.commit()
    with make_session() as session:
        session.add(a_sale())
        with pytest.raises(IntegrityError):
            session.commit()


def test_a_sale_of_no_bushels_is_refused(make_session):
    with make_session() as session:
        session.add(a_sale(bushels=Decimal("0")))
        with pytest.raises(IntegrityError):
            session.commit()


def test_negative_freight_is_refused_by_the_database(make_session):
    with make_session() as session:
        session.add(a_sale(freight_per_bushel=Decimal("-0.01")))
        with pytest.raises(IntegrityError):
            session.commit()


def test_a_window_that_closes_first_is_refused(make_session):
    with make_session() as session:
        session.add(
            a_sale(window_opens=date(2024, 12, 31), window_closes=date(2024, 11, 1))
        )
        with pytest.raises(IntegrityError):
            session.commit()


def test_a_shipment_hangs_off_its_sale(make_session):
    with make_session() as session:
        sale = a_sale()
        sale.shipments = [a_shipment()]
        session.add(sale)
        session.commit()
    with make_session() as session:
        found = session.scalar(select(SaleRow))
        assert len(found.shipments) == 1
        assert found.shipments[0].carrier_id == "BNSF 480221"


def test_a_reference_is_unique_within_a_sale(make_session):
    with make_session() as session:
        sale = a_sale()
        sale.shipments = [a_shipment(), a_shipment()]
        session.add(sale)
        with pytest.raises(IntegrityError):
            session.commit()


def test_the_same_reference_on_another_sale_is_fine(make_session):
    with make_session() as session:
        first = a_sale()
        first.shipments = [a_shipment()]
        second = a_sale(number="S-2024-0089")
        second.shipments = [a_shipment()]
        session.add_all([first, second])
        session.commit()
    with make_session() as session:
        assert len(session.scalars(select(ShipmentRow)).all()) == 2


def test_a_shipment_of_nothing_is_refused(make_session):
    with make_session() as session:
        sale = a_sale()
        sale.shipments = [a_shipment(origin_pounds=Decimal("0"))]
        session.add(sale)
        with pytest.raises(IntegrityError):
            session.commit()


def test_a_shipment_starts_unsettled_with_no_destination_weight(make_session):
    with make_session() as session:
        sale = a_sale()
        sale.shipments = [a_shipment()]
        session.add(sale)
        session.commit()
        shipment = sale.shipments[0]
        assert not shipment.is_settled
        assert shipment.transit_shrink is None
        assert not shipment.rejected


def test_transit_shrink_appears_once_the_destination_weight_does(make_session):
    with make_session() as session:
        sale = a_sale()
        sale.shipments = [
            a_shipment(
                destination_bushels=Decimal("3488.0000"),
                destination_pounds=Decimal("195328"),
                destination_grade="2",
                settled_on=date(2024, 11, 26),
            )
        ]
        session.add(sale)
        session.commit()
        shipment = sale.shipments[0]
        assert shipment.is_settled
        assert shipment.transit_shrink == Decimal("12.0000")


def test_a_destination_weighing_heavy_gives_a_negative_shrink(make_session):
    with make_session() as session:
        sale = a_sale()
        sale.shipments = [a_shipment(destination_bushels=Decimal("3510.0000"))]
        session.add(sale)
        session.commit()
        assert sale.shipments[0].transit_shrink == Decimal("-10.0000")


def test_a_rejected_car_keeps_its_reason(make_session):
    with make_session() as session:
        sale = a_sale()
        sale.shipments = [
            a_shipment(rejected=True, rejection_reason="16.8 moisture at destination")
        ]
        session.add(sale)
        session.commit()
        assert sale.shipments[0].rejected
        assert "16.8" in sale.shipments[0].rejection_reason


def test_deleting_a_sale_takes_its_shipments(make_session):
    with make_session() as session:
        sale = a_sale()
        sale.shipments = [a_shipment()]
        session.add(sale)
        session.commit()
        session.delete(sale)
        session.commit()
    with make_session() as session:
        assert session.scalars(select(ShipmentRow)).all() == []
