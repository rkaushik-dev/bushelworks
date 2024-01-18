from decimal import Decimal

import pytest

from bushelworks.money import (
    MoneyError,
    allocate,
    amount,
    extend,
    format_amount,
    format_price,
    negate,
    price,
    total,
)


def test_price_carries_four_decimals():
    assert price("4.7325") == Decimal("4.7325")
    assert price("-0.225") == Decimal("-0.2250")


def test_amount_rounds_half_up_to_cents():
    assert amount("12.345") == Decimal("12.35")
    assert amount("12.344") == Decimal("12.34")


def test_extend_rounds_once_at_the_end():
    # 932.8571 bu at 4.7325 is 4414.7462... and must not be rounded twice
    assert extend(Decimal("932.8571"), Decimal("4.7325")) == Decimal("4414.75")


def test_total_does_not_reround_parts():
    assert total([Decimal("1.005"), Decimal("1.005")]) == Decimal("2.01")


def test_allocate_splits_evenly_when_it_can():
    assert allocate(Decimal("100.00"), [Decimal(1), Decimal(1)]) == [
        Decimal("50.00"),
        Decimal("50.00"),
    ]


def test_allocate_gives_the_odd_cent_to_the_first_share():
    parts = allocate(Decimal("100.01"), [Decimal(1), Decimal(1)])
    assert parts == [Decimal("50.01"), Decimal("50.00")]
    assert sum(parts) == Decimal("100.01")


def test_allocate_three_way_thirds_still_balances():
    parts = allocate(Decimal("1000.00"), [Decimal(1), Decimal(1), Decimal(1)])
    assert sum(parts) == Decimal("1000.00")


def test_allocate_respects_uneven_weights():
    parts = allocate(Decimal("1000.00"), [Decimal("60"), Decimal("40")])
    assert parts == [Decimal("600.00"), Decimal("400.00")]


def test_allocate_handles_a_negative_amount():
    parts = allocate(Decimal("-100.01"), [Decimal(1), Decimal(1)])
    assert sum(parts) == Decimal("-100.01")


def test_allocate_rejects_zero_weights():
    with pytest.raises(MoneyError):
        allocate(Decimal("10.00"), [Decimal(0), Decimal(0)])


def test_allocate_rejects_no_weights():
    with pytest.raises(MoneyError):
        allocate(Decimal("10.00"), [])


def test_negate_and_formatting():
    assert negate(Decimal("12.34")) == Decimal("-12.34")
    assert format_amount(Decimal("-1234.5")) == "(1,234.50)"
    assert format_amount(Decimal("1234.5")) == "1,234.50"
    assert format_price(Decimal("4.7")) == "4.7000"
