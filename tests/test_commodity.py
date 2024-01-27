from decimal import Decimal

import pytest

from bushelworks.domain import commodity as cm


def test_master_holds_the_five_commodities_the_house_buys():
    assert cm.codes() == ("CORN", "HRW", "MILO", "SOYB", "SUNF")


def test_corn_is_fifty_six_pound_bushels_at_fifteen_percent():
    assert cm.CORN.standard_test_weight == Decimal("56")
    assert cm.CORN.base_moisture == Decimal("15.0")
    assert cm.CORN.price_unit is cm.PriceUnit.BUSHEL


def test_sunflower_is_priced_by_hundredweight():
    assert cm.SUNFLOWER.price_unit is cm.PriceUnit.HUNDREDWEIGHT


def test_graded_factors_is_required_plus_optional():
    assert cm.Factor.DAMAGE_HEAT in cm.CORN.graded_factors
    assert cm.Factor.DAMAGE_HEAT not in cm.CORN.required_factors
    assert cm.CORN.accepts(cm.Factor.BCFM)
    assert not cm.CORN.accepts(cm.Factor.PROTEIN)


def test_wheat_grades_dockage_and_corn_does_not():
    assert cm.HRW_WHEAT.accepts(cm.Factor.DOCKAGE)
    assert not cm.CORN.accepts(cm.Factor.DOCKAGE)


def test_unknown_commodity_raises():
    with pytest.raises(cm.UnknownCommodity):
        cm.get("BARLEY")


def test_a_factor_cannot_be_both_required_and_optional():
    with pytest.raises(ValueError):
        cm.Commodity(
            code="X",
            name="X",
            standard_test_weight=Decimal("56"),
            base_moisture=Decimal("15"),
            price_unit=cm.PriceUnit.BUSHEL,
            required_factors=frozenset({cm.Factor.MOISTURE}),
            optional_factors=frozenset({cm.Factor.MOISTURE}),
        )


def test_base_moisture_must_be_a_percentage():
    with pytest.raises(ValueError):
        cm.Commodity(
            code="X",
            name="X",
            standard_test_weight=Decimal("56"),
            base_moisture=Decimal("150"),
            price_unit=cm.PriceUnit.BUSHEL,
        )


def test_factor_direction_covers_every_factor():
    assert set(cm.FACTOR_DIRECTION) == set(cm.Factor)
    assert cm.FACTOR_DIRECTION[cm.Factor.TEST_WEIGHT] is cm.FactorDirection.LOW_IS_BAD
    assert cm.FACTOR_DIRECTION[cm.Factor.MOISTURE] is cm.FactorDirection.HIGH_IS_BAD
