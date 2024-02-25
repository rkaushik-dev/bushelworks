from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.pricing import (
    Basis,
    CashBid,
    FuturesMonth,
    PricedQuantity,
    PricingError,
    blend,
    next_month,
    order_months,
)

CZ4 = FuturesMonth("CORN", "Z", 2024)
CH5 = FuturesMonth("CORN", "H", 2025)
CN4 = FuturesMonth("CORN", "N", 2024)


def test_symbol_uses_the_two_digit_year():
    assert CZ4.symbol == "CORNZ24"
    assert CH5.symbol == "CORNH25"


def test_months_order_by_year_then_delivery_month():
    assert order_months([CH5, CZ4, CN4]) == [CN4, CZ4, CH5]
    assert CN4.is_before(CZ4)
    assert not CH5.is_before(CZ4)


def test_december_sorts_after_july_despite_the_letters():
    assert CN4.sort_key < CZ4.sort_key


def test_next_month_walks_the_delivery_cycle():
    assert next_month(CN4) == FuturesMonth("CORN", "U", 2024)
    assert next_month(FuturesMonth("CORN", "U", 2024)) == CZ4


def test_next_month_rolls_the_year_at_the_end_of_the_cycle():
    assert next_month(CZ4) == FuturesMonth("CORN", "H", 2025)


def test_corn_has_no_january_contract():
    with pytest.raises(PricingError):
        FuturesMonth("CORN", "F", 2024)


def test_soybeans_do_have_a_january_contract():
    assert FuturesMonth("SOYB", "F", 2025).month_number == 1


def test_an_unknown_month_letter_is_refused():
    with pytest.raises(PricingError):
        FuturesMonth("CORN", "B", 2024)


def test_first_notice_is_the_first_of_the_delivery_month():
    assert CZ4.first_notice() == date(2024, 12, 1)


def test_cash_is_futures_plus_basis():
    bid = CashBid(
        commodity_code="CORN",
        month=CZ4,
        futures=Decimal("4.5750"),
        basis=Decimal("-0.2250"),
        quoted_on=date(2024, 10, 3),
    )
    assert bid.cash == Decimal("4.3500")


def test_a_bid_never_goes_below_zero():
    bid = CashBid(
        commodity_code="CORN",
        month=CZ4,
        futures=Decimal("4.0000"),
        basis=Decimal("-4.5000"),
        quoted_on=date(2024, 10, 3),
    )
    assert bid.cash == Decimal("0.0000")


def test_with_basis_returns_a_new_bid():
    bid = CashBid(
        commodity_code="CORN",
        month=CZ4,
        futures=Decimal("4.5750"),
        basis=Decimal("-0.2250"),
        quoted_on=date(2024, 10, 3),
    )
    wider = bid.with_basis(Decimal("-0.4000"))
    assert wider.cash == Decimal("4.1750")
    assert bid.cash == Decimal("4.3500")


def test_a_bid_month_has_to_match_the_commodity():
    with pytest.raises(PricingError):
        CashBid(
            commodity_code="SOYB",
            month=CZ4,
            futures=Decimal("11.00"),
            basis=Decimal("-0.30"),
            quoted_on=date(2024, 10, 3),
        )


def test_futures_price_must_be_positive():
    with pytest.raises(PricingError):
        CashBid(
            commodity_code="CORN",
            month=CZ4,
            futures=Decimal("0"),
            basis=Decimal("-0.30"),
            quoted_on=date(2024, 10, 3),
        )


def test_an_implausible_basis_is_refused():
    with pytest.raises(PricingError):
        Basis(month=CZ4, value=Decimal("-9.00"), quoted_on=date(2024, 10, 3))


def test_priced_quantity_values_itself():
    lot = PricedQuantity(bushels=Decimal("932.8571"), unit_price=Decimal("4.3500"))
    assert lot.value == Decimal("4057.93")


def test_a_negative_quantity_cannot_be_priced():
    with pytest.raises(PricingError):
        PricedQuantity(bushels=Decimal("-1"), unit_price=Decimal("4.35"))


def test_blend_weights_by_bushels():
    lots = [
        PricedQuantity(Decimal("1000"), Decimal("4.2000")),
        PricedQuantity(Decimal("3000"), Decimal("4.6000")),
    ]
    assert blend(lots) == Decimal("4.5000")


def test_blend_needs_bushels_to_weight_with():
    with pytest.raises(PricingError):
        blend([])
