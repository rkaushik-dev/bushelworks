from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.freight import (
    FreightError,
    FreightRate,
    Haul,
    Lane,
    PaidBy,
    RateBasis,
    cheapest,
    invoice_total,
    reconcile_invoice,
)

SALINA = Lane(origin="HOME", destination="SALINA", loaded_miles=Decimal("62"))
EFFECTIVE = date(2024, 9, 1)

BUSHELS = Decimal("930.0000")
POUNDS = Decimal("52080")


def a_rate(**overrides) -> FreightRate:
    base = dict(
        carrier="Kruse Trucking",
        lane=SALINA,
        basis=RateBasis.PER_BUSHEL,
        rate=Decimal("0.1800"),
        effective_from=EFFECTIVE,
    )
    base.update(overrides)
    return FreightRate(**base)


def test_a_lane_needs_a_distance():
    with pytest.raises(FreightError):
        Lane(origin="HOME", destination="SALINA", loaded_miles=Decimal("0"))


def test_a_lane_has_to_go_somewhere():
    with pytest.raises(FreightError):
        Lane(origin="HOME", destination="HOME", loaded_miles=Decimal("10"))


def test_a_one_way_lane_bills_its_loaded_miles():
    assert SALINA.billed_miles == Decimal("62")
    assert SALINA.label == "HOME-SALINA"


def test_a_round_trip_lane_bills_both_ways():
    lane = Lane(
        origin="HOME", destination="SALINA", loaded_miles=Decimal("62"), is_round_trip=True
    )
    assert lane.billed_miles == Decimal("124")


def test_a_negative_rate_is_refused():
    with pytest.raises(FreightError):
        a_rate(rate=Decimal("-0.01"))


def test_an_implausible_fuel_surcharge_is_refused():
    with pytest.raises(FreightError):
        a_rate(fuel_surcharge_percent=Decimal("90"))


def test_a_per_bushel_rate_extends_over_bushels():
    assert a_rate().line_haul(BUSHELS, POUNDS) == Decimal("167.40")


def test_a_per_mile_rate_uses_the_billed_miles():
    rate = a_rate(basis=RateBasis.PER_MILE, rate=Decimal("2.9000"))
    assert rate.line_haul(BUSHELS, POUNDS) == Decimal("179.80")


def test_a_per_hundredweight_rate_uses_the_weight():
    rate = a_rate(basis=RateBasis.PER_HUNDREDWEIGHT, rate=Decimal("0.3200"))
    assert rate.line_haul(BUSHELS, POUNDS) == Decimal("166.66")


def test_a_flat_rate_ignores_the_load():
    rate = a_rate(basis=RateBasis.FLAT, rate=Decimal("200.00"))
    assert rate.line_haul(BUSHELS, POUNDS) == Decimal("200.00")
    assert rate.line_haul(Decimal("1"), Decimal("1")) == Decimal("200.00")


def test_a_negative_load_cannot_be_rated():
    with pytest.raises(FreightError):
        a_rate().line_haul(Decimal("-1"), POUNDS)


def test_no_surcharge_means_no_fuel_line():
    assert a_rate().fuel(BUSHELS, POUNDS) == Decimal("0.00")


def test_the_surcharge_is_a_percentage_of_the_line_haul():
    rate = a_rate(fuel_surcharge_percent=Decimal("18.5"))
    assert rate.fuel(BUSHELS, POUNDS) == Decimal("30.97")
    assert rate.total(BUSHELS, POUNDS) == Decimal("198.37")


def test_the_minimum_applies_to_a_small_load():
    rate = a_rate(minimum_charge=Decimal("250.00"))
    assert rate.total(BUSHELS, POUNDS) == Decimal("250.00")


def test_the_minimum_does_not_cap_a_big_load():
    rate = a_rate(minimum_charge=Decimal("100.00"))
    assert rate.total(BUSHELS, POUNDS) == Decimal("167.40")


def test_per_bushel_lets_two_bases_be_compared():
    per_bushel = a_rate()
    per_mile = a_rate(basis=RateBasis.PER_MILE, rate=Decimal("2.9000"))
    assert per_bushel.per_bushel(BUSHELS, POUNDS) < per_mile.per_bushel(BUSHELS, POUNDS)


def test_comparing_per_bushel_needs_a_real_load():
    with pytest.raises(FreightError):
        a_rate().per_bushel(Decimal("0"), POUNDS)


def test_the_cheapest_quote_is_found_for_a_real_load():
    quotes = [
        a_rate(),
        a_rate(carrier="Ostmeyer", basis=RateBasis.PER_MILE, rate=Decimal("2.9000")),
        a_rate(carrier="Dible", basis=RateBasis.FLAT, rate=Decimal("150.00")),
    ]
    assert cheapest(quotes, BUSHELS, POUNDS).carrier == "Dible"


def test_comparing_no_quotes_is_refused():
    with pytest.raises(FreightError):
        cheapest([], BUSHELS, POUNDS)


def a_haul(**overrides) -> Haul:
    base = dict(
        reference="T-4417",
        rate=a_rate(),
        bushels=BUSHELS,
        pounds=POUNDS,
        hauled_on=date(2024, 11, 12),
    )
    base.update(overrides)
    return Haul(**base)


def test_a_haul_carries_its_own_arithmetic():
    haul = a_haul()
    assert haul.line_haul == Decimal("167.40")
    assert haul.fuel == Decimal("0.00")
    assert haul.total == Decimal("167.40")


def test_a_normal_load_is_not_flagged_overweight():
    assert not a_haul().is_overweight


def test_a_very_heavy_load_is_flagged():
    assert a_haul(pounds=Decimal("62000")).is_overweight


def test_the_invoice_adds_the_hauls():
    hauls = [a_haul(), a_haul(reference="T-4418")]
    assert invoice_total(hauls) == Decimal("334.80")


def test_reconciling_a_matching_invoice_finds_nothing():
    hauls = [a_haul()]
    assert reconcile_invoice(hauls, Decimal("167.40")) == Decimal("0.00")


def test_reconciling_finds_an_overbilled_invoice():
    hauls = [a_haul()]
    assert reconcile_invoice(hauls, Decimal("198.37")) == Decimal("30.97")


def test_freight_defaults_to_the_house_paying():
    assert a_rate().paid_by is PaidBy.HOUSE
    assert a_rate(paid_by=PaidBy.PRODUCER).paid_by is PaidBy.PRODUCER
