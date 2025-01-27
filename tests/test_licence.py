from datetime import date
from decimal import Decimal

import pytest

from bushelworks.reports.licence import (
    TIGHT_MARGIN,
    LicenceError,
    Return,
    combine,
    over_capacity,
    render,
)

AS_OF = date(2024, 11, 30)


def a_return(**overrides) -> Return:
    base = dict(
        location_code="HOME",
        licence_number="KS-1174",
        as_of=AS_OF,
        licensed_capacity=Decimal("1450000"),
        storage_obligation=Decimal("400000"),
        deferred_price=Decimal("150000"),
        receipts_outstanding=Decimal("50000"),
        house_owned=Decimal("300000"),
    )
    base.update(overrides)
    return Return(**base)


def test_a_site_with_no_capacity_is_refused():
    with pytest.raises(LicenceError):
        a_return(licensed_capacity=Decimal("0"))


def test_a_negative_component_is_refused():
    with pytest.raises(LicenceError):
        a_return(storage_obligation=Decimal("-1"))


def test_the_obligation_is_more_than_the_storage():
    one = a_return()
    assert one.obligation == Decimal("600000.0000")
    assert one.obligation > one.storage_obligation


def test_the_total_held_adds_the_house_grain():
    assert a_return().total_held == Decimal("900000.0000")


def test_the_unused_capacity_is_what_is_left():
    assert a_return().unused_capacity == Decimal("550000.0000")


def test_a_normal_month_is_not_over_capacity():
    one = a_return()
    assert not one.is_over_capacity
    assert not one.is_tight


def test_a_full_house_is_over_capacity():
    one = a_return(house_owned=Decimal("1000000"))
    assert one.is_over_capacity
    assert one.unused_capacity < 0


def test_a_house_close_to_the_licence_is_tight():
    one = a_return(house_owned=Decimal("820000"))
    assert not one.is_over_capacity
    assert one.is_tight


def test_the_tight_margin_is_five_percent():
    assert TIGHT_MARGIN == Decimal("0.05")


def test_the_percentage_used_is_of_the_licence():
    assert a_return().percent_used == Decimal("62.1")


def test_the_return_names_the_site_and_the_licence():
    text = render(a_return())
    assert "WAREHOUSE RETURN 2024-11-30" in text
    assert "KS-1174" in text
    assert "HOME" in text


def test_the_return_fits_its_width():
    text = render(a_return())
    assert all(len(line) <= 72 for line in text.split("\n"))


def test_the_return_shows_the_obligation_separately():
    text = render(a_return())
    assert "total obligation" in text
    assert "house owned" in text


def test_an_over_capacity_return_says_so_loudly():
    text = render(a_return(house_owned=Decimal("1000000")))
    assert "OVER LICENSED CAPACITY" in text


def test_a_tight_return_carries_a_note():
    text = render(a_return(house_owned=Decimal("820000")))
    assert "Within five percent" in text


def test_a_comfortable_return_carries_no_note():
    text = render(a_return())
    assert "Within five percent" not in text
    assert "OVER LICENSED" not in text


def test_two_sites_combine_into_one_return():
    other = a_return(
        location_code="LURAY",
        licence_number="KS-1175",
        licensed_capacity=Decimal("620000"),
        storage_obligation=Decimal("100000"),
        deferred_price=Decimal("0"),
        receipts_outstanding=Decimal("0"),
        house_owned=Decimal("200000"),
    )
    whole = combine([a_return(), other], AS_OF)
    assert whole.location_code == "*"
    assert whole.licensed_capacity == Decimal("2070000")
    assert whole.total_held == Decimal("1200000.0000")


def test_the_combined_return_lists_both_licences():
    other = a_return(location_code="LURAY", licence_number="KS-1175")
    assert combine([a_return(), other], AS_OF).licence_number == "KS-1174, KS-1175"


def test_combining_nothing_is_refused():
    with pytest.raises(LicenceError):
        combine([], AS_OF)


def test_combining_different_months_is_refused():
    with pytest.raises(LicenceError):
        combine([a_return(), a_return(as_of=date(2024, 10, 31))], AS_OF)


def test_the_over_capacity_sites_are_found():
    full = a_return(location_code="LURAY", house_owned=Decimal("1000000"))
    found = over_capacity([a_return(), full])
    assert [one.location_code for one in found] == ["LURAY"]
