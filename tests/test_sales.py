from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.commodity import Factor
from bushelworks.domain.contracts import DeliveryWindow
from bushelworks.domain.pricing import FuturesMonth
from bushelworks.domain.sales import (
    Carrier,
    Destination,
    SaleContract,
    SaleError,
    SaleKind,
    Specification,
)

WINDOW = DeliveryWindow(date(2024, 11, 1), date(2024, 12, 31))

SPEC = Specification(
    max_moisture=Decimal("15.0"),
    min_test_weight=Decimal("54.0"),
    max_damage=Decimal("5.0"),
    max_foreign_material=Decimal("3.0"),
)


def a_sale(**overrides) -> SaleContract:
    base = dict(
        number="S-2024-0088",
        kind=SaleKind.FORWARD,
        buyer="Salina Terminal",
        destination=Destination.TERMINAL,
        commodity_code="CORN",
        origin_code="HOME",
        written_on=date(2024, 9, 5),
        bushels=Decimal("50000"),
        window=WINDOW,
        specification=SPEC,
        flat_price=Decimal("4.7800"),
        freight_per_bushel=Decimal("0.1800"),
    )
    base.update(overrides)
    return SaleContract(**base)


def a_sample(**overrides):
    sample = {
        Factor.MOISTURE: Decimal("14.2"),
        Factor.TEST_WEIGHT: Decimal("56.0"),
        Factor.DAMAGE_TOTAL: Decimal("2.0"),
        Factor.BCFM: Decimal("1.5"),
    }
    sample.update(overrides)
    return sample


def test_a_clean_load_meets_the_specification():
    assert SPEC.accepts(a_sample())
    assert SPEC.failures(a_sample()) == ()


def test_wet_grain_breaks_the_moisture_clause():
    assert SPEC.failures(a_sample(**{Factor.MOISTURE: Decimal("16.4")})) == ("moisture",)


def test_light_grain_breaks_the_test_weight_clause():
    assert SPEC.failures(a_sample(**{Factor.TEST_WEIGHT: Decimal("52.0")})) == (
        "test weight",
    )


def test_two_clauses_can_break_at_once():
    broken = SPEC.failures(
        a_sample(
            **{Factor.MOISTURE: Decimal("18.0"), Factor.DAMAGE_TOTAL: Decimal("7.0")}
        )
    )
    assert broken == ("moisture", "damage")


def test_bcfm_counts_as_foreign_material():
    assert "foreign material" in SPEC.failures(
        a_sample(**{Factor.BCFM: Decimal("4.5")})
    )


def test_a_factor_the_load_was_not_tested_for_cannot_break_a_clause():
    sample = a_sample()
    del sample[Factor.MOISTURE]
    assert "moisture" not in SPEC.failures(sample)


def test_a_nonsense_specification_is_refused():
    with pytest.raises(SaleError):
        Specification(
            max_moisture=Decimal("0"),
            min_test_weight=Decimal("54"),
            max_damage=Decimal("5"),
            max_foreign_material=Decimal("3"),
        )


def test_a_forward_sale_is_priced_when_written():
    sale = a_sale()
    assert sale.is_priced
    assert sale.unit_price == Decimal("4.7800")


def test_the_net_price_is_what_is_left_after_freight():
    assert a_sale().net_price == Decimal("4.6000")


def test_a_basis_sale_is_not_priced_until_the_futures_are_set():
    sale = a_sale(
        kind=SaleKind.BASIS,
        flat_price=None,
        futures_month=FuturesMonth("CORN", "Z", 2024),
        basis=Decimal("0.0800"),
    )
    assert not sale.is_priced
    with pytest.raises(SaleError):
        sale.unit_price
    priced = sale.set_futures(Decimal("4.7000"))
    assert priced.unit_price == Decimal("4.7800")


def test_only_a_basis_sale_sets_futures_later():
    with pytest.raises(SaleError):
        a_sale().set_futures(Decimal("4.70"))


def test_the_futures_can_only_be_set_once():
    sale = a_sale(
        kind=SaleKind.BASIS,
        flat_price=None,
        basis=Decimal("0.08"),
    ).set_futures(Decimal("4.70"))
    with pytest.raises(SaleError):
        sale.set_futures(Decimal("4.75"))


def test_a_sale_with_no_price_at_all_is_refused():
    with pytest.raises(SaleError):
        a_sale(flat_price=None)


def test_a_sale_of_no_bushels_is_refused():
    with pytest.raises(SaleError):
        a_sale(bushels=Decimal("0"))


def test_negative_freight_is_refused():
    with pytest.raises(SaleError):
        a_sale(freight_per_bushel=Decimal("-0.01"))


def test_a_new_sale_has_everything_left_to_load():
    assert a_sale().remaining == Decimal("50000.0000")
    assert not a_sale().is_complete


def test_shipping_reduces_what_is_left():
    sale = a_sale().ship(Decimal("20000"), date(2024, 11, 15))
    assert sale.shipped == Decimal("20000.0000")
    assert sale.remaining == Decimal("30000.0000")


def test_a_sale_is_complete_at_the_face_amount_not_the_tolerance():
    sale = a_sale().ship(Decimal("49000"), date(2024, 11, 15))
    assert not sale.is_complete
    assert sale.ship(Decimal("1000"), date(2024, 11, 20)).is_complete


def test_the_tolerance_lets_the_last_car_run_heavy():
    sale = a_sale().ship(Decimal("50000"), date(2024, 11, 15))
    assert sale.can_ship(Decimal("900"), date(2024, 11, 20))
    assert not sale.can_ship(Decimal("1100"), date(2024, 11, 20))


def test_shipping_past_the_tolerance_is_refused():
    sale = a_sale().ship(Decimal("50000"), date(2024, 11, 15))
    with pytest.raises(SaleError):
        sale.ship(Decimal("2000"), date(2024, 11, 20))


def test_shipping_outside_the_window_is_refused():
    with pytest.raises(SaleError):
        a_sale().ship(Decimal("1000"), date(2025, 1, 15))
    assert not a_sale().can_ship(Decimal("1000"), date(2025, 1, 15))


def test_shipping_nothing_is_refused():
    with pytest.raises(SaleError):
        a_sale().ship(Decimal("0"), date(2024, 11, 15))


def test_the_carrier_defaults_to_truck():
    assert a_sale().carrier is Carrier.TRUCK
    assert a_sale(carrier=Carrier.RAIL).carrier is Carrier.RAIL
