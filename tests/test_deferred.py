from datetime import date
from decimal import Decimal

import pytest

from bushelworks.errors import ConflictError
from bushelworks.persistence.base import create_all, drop_all, engine_for, session_factory
from bushelworks.persistence.unitofwork import unit_of_work
from bushelworks.services.deferred import (
    DEFAULT_TERMS,
    DeferredPriceError,
    ServiceTerms,
    deliver,
    house_exposure,
    overdue,
    position,
    price_lot,
    total_unpriced,
    unpriced_lots,
)
from bushelworks.services.warehousing import store, withdraw

DELIVERED = date(2024, 10, 3)


@pytest.fixture
def factory():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    yield session_factory(engine)
    drop_all(engine)


@pytest.fixture
def delivered(factory):
    with unit_of_work(factory) as uow:
        deliver(uow, "D-1", "MERRICK", "CORN", "HOME", Decimal("10000"), DELIVERED)
        deliver(
            uow, "D-2", "MERRICK", "CORN", "HOME", Decimal("5000"), date(2024, 11, 12)
        )
        store(uow, "L-9", "MERRICK", "CORN", "HOME", Decimal("3000"), DELIVERED)
        uow.commit()
    return factory


def test_service_terms_refuse_a_negative_rate():
    with pytest.raises(DeferredPriceError):
        ServiceTerms(rate_per_bushel_per_month=Decimal("-0.01"))


def test_the_free_period_comes_off_the_front():
    terms = ServiceTerms(rate_per_bushel_per_month=Decimal("0.03"), free_days=15)
    assert terms.chargeable_days(DELIVERED, date(2024, 10, 18)) == 0
    assert terms.chargeable_days(DELIVERED, date(2024, 11, 2)) == 15


def test_the_fee_is_prorated_by_the_day():
    # 10000 bu, 30 days at 0.03 a month
    assert DEFAULT_TERMS.charge(
        Decimal("10000"), DELIVERED, date(2024, 11, 2)
    ) == Decimal("300.00")


def test_charging_before_delivery_is_refused():
    with pytest.raises(DeferredPriceError):
        DEFAULT_TERMS.charge(Decimal("100"), DELIVERED, date(2024, 10, 1))


def test_a_term_limit_makes_a_lot_overdue():
    assert not DEFAULT_TERMS.is_overdue(DELIVERED, date(2025, 10, 2))
    assert DEFAULT_TERMS.is_overdue(DELIVERED, date(2025, 10, 4))


def test_terms_with_no_limit_never_go_overdue():
    terms = ServiceTerms(rate_per_bushel_per_month=Decimal("0.03"))
    assert not terms.is_overdue(DELIVERED, date(2030, 1, 1))


def test_deferred_lots_are_separate_from_storage(delivered):
    with unit_of_work(delivered) as uow:
        assert [row.lot_id for row in unpriced_lots(uow)] == ["D-1", "D-2"]


def test_pricing_returns_the_gross_and_the_fee(delivered):
    with unit_of_work(delivered) as uow:
        gross, fee = price_lot(
            uow, "D-1", Decimal("4000"), Decimal("4.3500"), date(2024, 11, 2)
        )
    assert gross == Decimal("17400.00")
    assert fee == Decimal("120.00")


def test_the_fee_is_charged_only_on_the_bushels_being_priced(delivered):
    with unit_of_work(delivered) as uow:
        _, all_of_it = price_lot(
            uow, "D-1", Decimal("10000"), Decimal("4.35"), date(2024, 11, 2)
        )
        _, some_of_it = price_lot(
            uow, "D-1", Decimal("4000"), Decimal("4.35"), date(2024, 11, 2)
        )
    assert some_of_it < all_of_it


def test_pricing_more_than_is_open_is_refused(delivered):
    with unit_of_work(delivered) as uow:
        with pytest.raises(DeferredPriceError):
            price_lot(
                uow, "D-1", Decimal("10001"), Decimal("4.35"), date(2024, 11, 2)
            )


def test_pricing_nothing_is_refused(delivered):
    with unit_of_work(delivered) as uow:
        with pytest.raises(DeferredPriceError):
            price_lot(uow, "D-1", Decimal("0"), Decimal("4.35"), date(2024, 11, 2))


def test_a_price_of_zero_is_refused(delivered):
    with unit_of_work(delivered) as uow:
        with pytest.raises(DeferredPriceError):
            price_lot(uow, "D-1", Decimal("100"), Decimal("0"), date(2024, 11, 2))


def test_pricing_a_storage_lot_is_refused(delivered):
    with unit_of_work(delivered) as uow:
        with pytest.raises(DeferredPriceError):
            price_lot(uow, "L-9", Decimal("100"), Decimal("4.35"), date(2024, 11, 2))


def test_pricing_before_delivery_is_refused(delivered):
    with unit_of_work(delivered) as uow:
        with pytest.raises(ConflictError):
            price_lot(uow, "D-1", Decimal("100"), Decimal("4.35"), date(2024, 9, 1))


def test_a_producer_position_adds_the_lots_up(delivered):
    with unit_of_work(delivered) as uow:
        found = position(uow, "MERRICK", "CORN", date(2024, 11, 12))
    assert found.bushels == Decimal("15000.0000")
    assert found.oldest_delivery == DELIVERED
    assert found.service_owed > Decimal("0")
    assert not found.is_overdue


def test_a_producer_with_nothing_unpriced_has_no_position(delivered):
    with unit_of_work(delivered) as uow:
        assert position(uow, "HOLLIS", "CORN", date(2024, 11, 12)) is None


def test_a_position_reports_which_lots_are_overdue(delivered):
    with unit_of_work(delivered) as uow:
        found = position(uow, "MERRICK", "CORN", date(2025, 12, 1))
    assert found.is_overdue
    assert found.overdue_lots == ("D-1", "D-2")


def test_the_house_is_long_the_unpriced_bushels(delivered):
    with unit_of_work(delivered) as uow:
        assert house_exposure(uow, "CORN", date(2024, 11, 12)) == Decimal("15000.0000")


def test_exposure_ignores_deliveries_after_the_day_asked_about(delivered):
    with unit_of_work(delivered) as uow:
        assert house_exposure(uow, "CORN", date(2024, 10, 15)) == Decimal("10000.0000")


def test_storage_bushels_are_not_house_exposure(delivered):
    with unit_of_work(delivered) as uow:
        exposure = house_exposure(uow, "CORN", date(2024, 11, 12))
    assert exposure == Decimal("15000.0000")


def test_the_overdue_list_is_empty_inside_the_term(delivered):
    with unit_of_work(delivered) as uow:
        assert overdue(uow, date(2025, 1, 1)) == []


def test_the_overdue_list_names_the_lots_past_the_term(delivered):
    with unit_of_work(delivered) as uow:
        assert [row.lot_id for row in overdue(uow, date(2025, 12, 1))] == ["D-1", "D-2"]


def test_total_unpriced_drops_as_bushels_are_taken_out(delivered):
    with unit_of_work(delivered) as uow:
        assert total_unpriced(uow) == Decimal("15000.0000")
        withdraw(uow, "MERRICK", "CORN", Decimal("3000"), date(2024, 12, 1))
        uow.commit()
    with unit_of_work(delivered) as uow:
        assert total_unpriced(uow) < Decimal("15000.0000")
