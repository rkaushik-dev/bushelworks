from datetime import date
from decimal import Decimal

import pytest

from bushelworks.errors import ConflictError
from bushelworks.persistence.base import create_all, drop_all, engine_for, session_factory
from bushelworks.persistence.models import SaleRow
from bushelworks.persistence.unitofwork import unit_of_work
from bushelworks.services.shipping import (
    SaleNotFound,
    ShippingError,
    freight_owed,
    load_out,
    open_sales,
    reject_shipment,
    sale,
    settle_shipment,
    shipped_bushels,
    sold_forward,
    transit_loss,
)

SHIPPED = date(2024, 11, 12)


@pytest.fixture
def factory():
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
        bushels=Decimal("10000"),
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


@pytest.fixture
def sold(factory):
    with unit_of_work(factory) as uow:
        uow.session.add(a_sale())
        uow.session.add(a_sale(number="S-2024-0089", commodity_code="SOYB"))
        uow.commit()
    return factory


def test_a_missing_sale_says_so(sold):
    with unit_of_work(sold) as uow:
        with pytest.raises(SaleNotFound):
            sale(uow, "S-9999")


def test_open_sales_are_filtered_by_commodity(sold):
    with unit_of_work(sold) as uow:
        assert [row.number for row in open_sales(uow, "CORN")] == ["S-2024-0088"]


def test_open_sales_are_filtered_by_the_window(sold):
    with unit_of_work(sold) as uow:
        assert open_sales(uow, "CORN", date(2025, 3, 1)) == []


def test_loading_out_moves_the_shipped_total(sold):
    with unit_of_work(sold) as uow:
        loaded = load_out(uow, "S-2024-0088", "CAR-1", Decimal("196000"), SHIPPED)
        uow.commit()
    assert loaded.bushels == Decimal("3500.0000")
    assert loaded.remaining == Decimal("6500.0000")


def test_the_shipment_row_is_written(sold):
    with unit_of_work(sold) as uow:
        load_out(uow, "S-2024-0088", "CAR-1", Decimal("196000"), SHIPPED, "BNSF 480221")
        uow.commit()
    with unit_of_work(sold) as uow:
        row = sale(uow, "S-2024-0088")
        assert len(row.shipments) == 1
        assert row.shipments[0].carrier_id == "BNSF 480221"


def test_filling_a_sale_closes_it(sold):
    with unit_of_work(sold) as uow:
        load_out(uow, "S-2024-0088", "CAR-1", Decimal("560000"), SHIPPED)
        uow.commit()
    with unit_of_work(sold) as uow:
        assert sale(uow, "S-2024-0088").status == "filled"


def test_a_filled_sale_takes_no_more(sold):
    with unit_of_work(sold) as uow:
        load_out(uow, "S-2024-0088", "CAR-1", Decimal("560000"), SHIPPED)
        with pytest.raises(ConflictError):
            load_out(uow, "S-2024-0088", "CAR-2", Decimal("10000"), SHIPPED)


def test_loading_past_the_tolerance_is_refused(sold):
    with unit_of_work(sold) as uow:
        with pytest.raises(ShippingError):
            load_out(uow, "S-2024-0088", "CAR-1", Decimal("600000"), SHIPPED)


def test_loading_outside_the_window_is_refused(sold):
    with unit_of_work(sold) as uow:
        with pytest.raises(ShippingError):
            load_out(uow, "S-2024-0088", "CAR-1", Decimal("196000"), date(2025, 3, 1))


def test_a_load_of_nothing_is_refused(sold):
    with unit_of_work(sold) as uow:
        with pytest.raises(ShippingError):
            load_out(uow, "S-2024-0088", "CAR-1", Decimal("0"), SHIPPED)


def test_the_same_reference_twice_is_a_conflict(sold):
    with unit_of_work(sold) as uow:
        load_out(uow, "S-2024-0088", "CAR-1", Decimal("196000"), SHIPPED)
        with pytest.raises(ConflictError):
            load_out(uow, "S-2024-0088", "CAR-1", Decimal("196000"), SHIPPED)


def test_the_buyers_settlement_posts_the_destination_weight(sold):
    with unit_of_work(sold) as uow:
        load_out(uow, "S-2024-0088", "CAR-1", Decimal("196000"), SHIPPED)
        shipment = settle_shipment(
            uow,
            "S-2024-0088",
            "CAR-1",
            Decimal("195328"),
            "2",
            Decimal("16730.00"),
            date(2024, 11, 26),
        )
        uow.commit()
    assert shipment.is_settled
    assert shipment.destination_grade == "2"
    assert shipment.transit_shrink == Decimal("12.0000")


def test_settling_twice_is_a_conflict(sold):
    with unit_of_work(sold) as uow:
        load_out(uow, "S-2024-0088", "CAR-1", Decimal("196000"), SHIPPED)
        settle_shipment(
            uow,
            "S-2024-0088",
            "CAR-1",
            Decimal("195328"),
            "2",
            Decimal("16730.00"),
            date(2024, 11, 26),
        )
        with pytest.raises(ConflictError):
            settle_shipment(
                uow,
                "S-2024-0088",
                "CAR-1",
                Decimal("195328"),
                "2",
                Decimal("16730.00"),
                date(2024, 11, 27),
            )


def test_settling_a_shipment_nobody_loaded_is_refused(sold):
    with unit_of_work(sold) as uow:
        with pytest.raises(SaleNotFound):
            settle_shipment(
                uow,
                "S-2024-0088",
                "CAR-9",
                Decimal("195328"),
                "2",
                Decimal("16730.00"),
                date(2024, 11, 26),
            )


def test_a_rejected_car_comes_back_onto_the_sale(sold):
    with unit_of_work(sold) as uow:
        load_out(uow, "S-2024-0088", "CAR-1", Decimal("560000"), SHIPPED)
        assert sale(uow, "S-2024-0088").status == "filled"
        reject_shipment(uow, "S-2024-0088", "CAR-1", "16.8 moisture at destination")
        uow.commit()
    with unit_of_work(sold) as uow:
        row = sale(uow, "S-2024-0088")
        assert row.status == "open"
        assert row.shipped == Decimal("0.0000")


def test_rejecting_twice_is_a_conflict(sold):
    with unit_of_work(sold) as uow:
        load_out(uow, "S-2024-0088", "CAR-1", Decimal("196000"), SHIPPED)
        reject_shipment(uow, "S-2024-0088", "CAR-1", "wet")
        with pytest.raises(ConflictError):
            reject_shipment(uow, "S-2024-0088", "CAR-1", "wet again")


def test_a_rejection_needs_a_reason(sold):
    with unit_of_work(sold) as uow:
        load_out(uow, "S-2024-0088", "CAR-1", Decimal("196000"), SHIPPED)
        with pytest.raises(ShippingError):
            reject_shipment(uow, "S-2024-0088", "CAR-1", "")


def test_shipped_bushels_ignores_rejected_cars(sold):
    with unit_of_work(sold) as uow:
        load_out(uow, "S-2024-0088", "CAR-1", Decimal("196000"), SHIPPED)
        load_out(uow, "S-2024-0088", "CAR-2", Decimal("196000"), SHIPPED)
        reject_shipment(uow, "S-2024-0088", "CAR-2", "wet")
        uow.commit()
    with unit_of_work(sold) as uow:
        assert shipped_bushels(uow, "CORN", date(2024, 12, 1)) == Decimal("3500.0000")


def test_sold_forward_is_what_has_not_gone_out(sold):
    with unit_of_work(sold) as uow:
        assert sold_forward(uow, "CORN", SHIPPED) == Decimal("10000.0000")
        load_out(uow, "S-2024-0088", "CAR-1", Decimal("196000"), SHIPPED)
        assert sold_forward(uow, "CORN", SHIPPED) == Decimal("6500.0000")


def test_transit_loss_adds_the_settled_cars(sold):
    with unit_of_work(sold) as uow:
        load_out(uow, "S-2024-0088", "CAR-1", Decimal("196000"), SHIPPED)
        settle_shipment(
            uow,
            "S-2024-0088",
            "CAR-1",
            Decimal("195328"),
            "2",
            Decimal("16730.00"),
            date(2024, 11, 26),
        )
        assert transit_loss(uow, "S-2024-0088") == Decimal("12.0000")


def test_an_unsettled_car_contributes_no_transit_loss(sold):
    with unit_of_work(sold) as uow:
        load_out(uow, "S-2024-0088", "CAR-1", Decimal("196000"), SHIPPED)
        assert transit_loss(uow, "S-2024-0088") == Decimal("0.0000")


def test_freight_is_owed_on_what_went_out(sold):
    with unit_of_work(sold) as uow:
        load_out(uow, "S-2024-0088", "CAR-1", Decimal("196000"), SHIPPED)
        assert freight_owed(uow, "S-2024-0088") == Decimal("630.00")
