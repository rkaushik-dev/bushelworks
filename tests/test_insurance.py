from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.insurance import (
    Delivery,
    InsuranceError,
    Production,
    Unit,
    build,
    claims,
    reconcile,
    to_csv,
)
from bushelworks.errors import ConflictError


def a_unit(**overrides) -> Unit:
    base = dict(
        unit_number="0001-0001",
        party_code="MERRICK",
        commodity_code="CORN",
        crop_year=2024,
        acres=Decimal("640"),
        approved_yield=Decimal("165"),
        farm_codes=frozenset({"HOME-PLACE", "NORTH-EIGHTY"}),
    )
    base.update(overrides)
    return Unit(**base)


def a_delivery(number="A-10441", farm="HOME-PLACE", bushels="20000") -> Delivery:
    return Delivery(
        ticket_number=number,
        farm_code=farm,
        scale_date=date(2024, 10, 3),
        bushels=Decimal(bushels),
    )


def test_a_unit_needs_a_number_acres_and_a_yield():
    with pytest.raises(InsuranceError):
        a_unit(unit_number="")
    with pytest.raises(InsuranceError):
        a_unit(acres=Decimal("0"))
    with pytest.raises(InsuranceError):
        a_unit(approved_yield=Decimal("0"))


def test_the_guarantee_is_acres_times_the_approved_yield():
    assert a_unit().guaranteed_bushels == Decimal("105600.0000")


def test_a_unit_covers_the_farms_on_it():
    unit = a_unit()
    assert unit.covers("HOME-PLACE")
    assert not unit.covers("SOUTH-QUARTER")


def test_a_delivery_of_negative_bushels_is_refused():
    with pytest.raises(InsuranceError):
        a_delivery(bushels="-1")


def test_a_delivery_from_a_farm_the_unit_does_not_cover_is_refused():
    production = Production(unit=a_unit())
    with pytest.raises(ConflictError):
        production.add(a_delivery(farm="SOUTH-QUARTER"))


def test_the_same_ticket_twice_is_refused():
    production = Production(unit=a_unit())
    production.add(a_delivery())
    with pytest.raises(ConflictError):
        production.add(a_delivery())


def test_the_delivered_bushels_add_up():
    production = build(
        a_unit(), [a_delivery(), a_delivery("A-10442", "NORTH-EIGHTY", "30000")]
    )
    assert production.delivered == Decimal("50000.0000")


def test_deliveries_from_other_farms_are_left_out():
    production = build(
        a_unit(), [a_delivery(), a_delivery("A-10442", "SOUTH-QUARTER", "30000")]
    )
    assert production.delivered == Decimal("20000.0000")


def test_farm_stored_grain_counts_toward_production():
    production = build(a_unit(), [a_delivery()], farm_stored=Decimal("40000"))
    assert production.total == Decimal("60000.0000")
    assert production.delivered == Decimal("20000.0000")


def test_the_yield_per_acre_is_of_everything_produced():
    production = build(a_unit(), [a_delivery("A-1", "HOME-PLACE", "64000")])
    assert production.yield_per_acre == Decimal("100.00")


def test_a_short_year_has_a_claim():
    production = build(a_unit(), [a_delivery("A-1", "HOME-PLACE", "60000")])
    assert production.has_a_claim
    assert production.shortfall == Decimal("45600.0000")


def test_a_good_year_has_no_claim():
    production = build(a_unit(), [a_delivery("A-1", "HOME-PLACE", "120000")])
    assert not production.has_a_claim
    assert production.shortfall == Decimal("0.0000")


def test_a_year_exactly_on_the_guarantee_has_no_claim():
    production = build(a_unit(), [a_delivery("A-1", "HOME-PLACE", "105600")])
    assert not production.has_a_claim


def test_the_percentage_of_the_guarantee_is_reported():
    production = build(a_unit(), [a_delivery("A-1", "HOME-PLACE", "52800")])
    assert production.percent_of_guarantee == Decimal("50.0")


def test_a_unit_with_nothing_delivered_is_wholly_short():
    production = build(a_unit(), [])
    assert production.shortfall == a_unit().guaranteed_bushels
    assert production.percent_of_guarantee == Decimal("0.0")


def test_the_report_has_a_row_per_unit():
    first = build(a_unit(), [a_delivery()])
    second = build(a_unit(unit_number="0001-0002"), [])
    text = to_csv([first, second])
    rows = text.strip().split("\n")
    assert rows[0].startswith("unit_number,party_code")
    assert len(rows) == 3


def test_the_report_carries_the_guarantee_and_the_shortfall():
    text = to_csv([build(a_unit(), [a_delivery()])])
    assert "105600.0000" in text
    assert "85600.0000" in text


def test_the_claims_are_biggest_shortfall_first():
    small = build(a_unit(), [a_delivery("A-1", "HOME-PLACE", "100000")])
    large = build(a_unit(unit_number="0001-0002"), [])
    found = claims([small, large])
    assert [item.unit.unit_number for item in found] == ["0001-0002", "0001-0001"]


def test_a_book_with_no_claims_comes_back_empty():
    assert claims([build(a_unit(), [a_delivery("A-1", "HOME-PLACE", "120000")])]) == []


def test_reconciling_against_the_settlements_should_come_out_at_nothing():
    production = build(a_unit(), [a_delivery()])
    assert reconcile(production, Decimal("20000")) == Decimal("0.0000")


def test_a_ticket_with_the_wrong_farm_shows_up_as_a_gap():
    production = build(a_unit(), [a_delivery()])
    assert reconcile(production, Decimal("50000")) == Decimal("-30000.0000")
