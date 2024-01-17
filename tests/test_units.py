from decimal import Decimal

import pytest

from bushelworks.units import (
    UnitError,
    bushels_to_pounds,
    net_pounds,
    percent_of,
    pounds_to_bushels,
    pounds_to_cwt,
    pounds_to_tons,
    to_pounds,
)


def test_to_pounds_rounds_half_up():
    assert to_pounds("52340.5") == Decimal("52341")
    assert to_pounds("52340.4") == Decimal("52340")


def test_to_pounds_rejects_negative():
    with pytest.raises(UnitError):
        to_pounds("-1")


def test_net_pounds_subtracts():
    assert net_pounds(78420, 26180) == Decimal("52240")


def test_net_pounds_rejects_tare_above_gross():
    with pytest.raises(UnitError):
        net_pounds(26180, 78420)


def test_corn_bushels_at_standard_test_weight():
    # 52240 lb of corn at 56 lb/bu
    assert pounds_to_bushels(52240, Decimal("56")) == Decimal("932.8571")


def test_soybean_bushels_at_sixty():
    assert pounds_to_bushels(48000, Decimal("60")) == Decimal("800.0000")


def test_bushels_round_trip_within_a_pound():
    bu = pounds_to_bushels(52240, Decimal("56"))
    assert bushels_to_pounds(bu, Decimal("56")) == Decimal("52240")


def test_zero_standard_test_weight_is_rejected():
    with pytest.raises(UnitError):
        pounds_to_bushels(1000, Decimal("0"))


def test_cwt_and_tons():
    assert pounds_to_cwt(52240) == Decimal("522.40")
    assert pounds_to_tons(52240) == Decimal("26.120")


def test_percent_of_uses_bushel_precision_by_default():
    assert percent_of(Decimal("932.8571"), Decimal("1.5")) == Decimal("13.9929")
