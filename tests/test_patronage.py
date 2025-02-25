from datetime import date
from decimal import Decimal

import pytest

from bushelworks.reports.patronage import (
    Line,
    PatronageError,
    Year,
    combine,
    render,
    to_csv,
)

OPENS = date(2024, 9, 1)
CLOSES = date(2025, 8, 31)


def a_line(**overrides) -> Line:
    base = dict(
        party_code="MERRICK",
        name="Merrick Farms",
        bushels=Decimal("124000.00"),
        gross=Decimal("508400.00"),
        charges=Decimal("9920.00"),
        loads=88,
    )
    base.update(overrides)
    return Line(**base)


def a_year(*lines: Line, location_code: str = "HOME") -> Year:
    return Year(
        location_code=location_code,
        opens=OPENS,
        closes=CLOSES,
        lines=lines or (a_line(),),
    )


def test_negative_bushels_are_refused():
    with pytest.raises(PatronageError):
        a_line(bushels=Decimal("-1"))


def test_negative_loads_are_refused():
    with pytest.raises(PatronageError):
        a_line(loads=-1)


def test_a_year_cannot_close_before_it_opens():
    with pytest.raises(PatronageError):
        Year(location_code="HOME", opens=CLOSES, closes=OPENS)


def test_a_producer_cannot_appear_twice():
    with pytest.raises(PatronageError):
        a_year(a_line(), a_line())


def test_the_margin_is_what_the_house_kept():
    assert a_line().margin == Decimal("9920.00")


def test_margin_per_bushel_carries_four_places():
    assert a_line().margin_per_bushel == Decimal("0.0800")


def test_a_producer_with_no_bushels_has_no_margin_per_bushel():
    assert a_line(bushels=Decimal("0")).margin_per_bushel == Decimal("0.0000")


def test_the_average_load_is_bushels_over_loads():
    assert a_line(bushels=Decimal("8800"), loads=8).average_load == Decimal("1100.00")


def test_a_producer_with_no_loads_has_no_average():
    assert a_line(loads=0).average_load == Decimal("0.00")


def test_a_year_totals_its_lines():
    year = a_year(a_line(), a_line(party_code="HOLLIS", bushels=Decimal("40000")))
    assert year.bushels == Decimal("164000.00")
    assert year.gross == Decimal("1016800.00")
    assert year.margin == Decimal("19840.00")


def test_the_bushel_ranking_puts_the_biggest_first():
    year = a_year(
        a_line(party_code="HOLLIS", bushels=Decimal("40000")),
        a_line(),
    )
    assert [line.party_code for line in year.by_bushels()] == ["MERRICK", "HOLLIS"]


def test_a_tie_on_bushels_breaks_on_the_code():
    year = a_year(
        a_line(party_code="ZANDER"),
        a_line(party_code="ABBOTT"),
    )
    assert [line.party_code for line in year.by_bushels()] == ["ABBOTT", "ZANDER"]


def test_the_margin_ranking_can_differ_from_the_bushel_ranking():
    year = a_year(
        a_line(),
        a_line(
            party_code="HOLLIS",
            bushels=Decimal("60000"),
            charges=Decimal("14400.00"),
        ),
    )
    assert year.by_bushels()[0].party_code == "MERRICK"
    assert year.by_margin()[0].party_code == "HOLLIS"


def test_non_members_are_left_off_the_member_list():
    year = a_year(a_line(), a_line(party_code="HOLLIS", is_member=False))
    assert [line.party_code for line in year.members()] == ["MERRICK"]


def test_a_share_is_a_percentage_of_the_year():
    year = a_year(
        a_line(bushels=Decimal("75000")),
        a_line(party_code="HOLLIS", bushels=Decimal("25000")),
    )
    assert year.share_of_bushels("HOLLIS") == Decimal("25.0000")


def test_a_share_of_an_empty_year_is_nothing():
    year = a_year(a_line(bushels=Decimal("0")))
    assert year.share_of_bushels("MERRICK") == Decimal("0.0000")


def test_asking_for_somebody_who_did_not_deliver_is_refused():
    with pytest.raises(PatronageError):
        a_year().share_of_bushels("NOBODY")


def test_concentration_counts_the_biggest_handful():
    lines = [
        a_line(party_code=f"P{index:02d}", bushels=Decimal("10000"))
        for index in range(20)
    ]
    assert a_year(*lines).concentration(count=10) == Decimal("50.0000")


def test_concentration_of_an_empty_year_is_nothing():
    assert a_year(a_line(bushels=Decimal("0"))).concentration() == Decimal("0.0000")


def test_a_pool_splits_on_member_bushels():
    year = a_year(
        a_line(bushels=Decimal("75000")),
        a_line(party_code="HOLLIS", bushels=Decimal("25000")),
    )
    split = year.allocations(Decimal("40000.00"))
    assert split == {
        "MERRICK": Decimal("30000.00"),
        "HOLLIS": Decimal("10000.00"),
    }


def test_a_pool_ignores_non_members():
    year = a_year(
        a_line(bushels=Decimal("50000")),
        a_line(party_code="HOLLIS", bushels=Decimal("50000"), is_member=False),
    )
    assert year.allocations(Decimal("1000.00")) == {"MERRICK": Decimal("1000.00")}


def test_the_pool_always_adds_back_to_itself():
    year = a_year(
        a_line(bushels=Decimal("33333")),
        a_line(party_code="HOLLIS", bushels=Decimal("33333")),
        a_line(party_code="ABBOTT", bushels=Decimal("33334")),
    )
    split = year.allocations(Decimal("1000.00"))
    assert sum(split.values()) == Decimal("1000.00")


def test_the_rounding_remainder_lands_on_the_biggest_member():
    year = a_year(
        a_line(bushels=Decimal("33333")),
        a_line(party_code="HOLLIS", bushels=Decimal("33333")),
        a_line(party_code="ABBOTT", bushels=Decimal("33334")),
    )
    split = year.allocations(Decimal("1000.00"))
    assert split["ABBOTT"] > split["HOLLIS"]


def test_a_pool_with_no_members_splits_nowhere():
    year = a_year(a_line(is_member=False))
    assert year.allocations(Decimal("1000.00")) == {}


def test_the_page_fits_eighty_columns():
    lines = [
        a_line(
            party_code=f"PRODUCER{index:02d}",
            name="A Very Long Farm Name Indeed LLC",
            bushels=Decimal("999999.99"),
            charges=Decimal("199999.99"),
        )
        for index in range(12)
    ]
    for row in render(a_year(*lines)).splitlines():
        assert len(row) <= 80


def test_the_page_names_the_house_and_the_year():
    page = render(a_year())
    assert "HOME" in page
    assert "2024-09-01 to 2025-08-31" in page


def test_the_page_can_be_ranked_on_margin():
    assert "PATRONAGE BY MARGIN" in render(a_year(), order="margin")


def test_the_page_refuses_an_order_it_does_not_know():
    with pytest.raises(PatronageError):
        render(a_year(), order="alphabetical")


def test_small_producers_collapse_into_one_line():
    year = a_year(
        a_line(),
        a_line(party_code="TINY", bushels=Decimal("400"), charges=Decimal("30.00")),
    )
    page = render(year)
    assert "1 others" in page
    assert "TINY" not in page


def test_the_limit_pushes_the_rest_into_others():
    lines = [
        a_line(party_code=f"P{index:02d}", bushels=Decimal("10000") + index)
        for index in range(6)
    ]
    page = render(a_year(*lines), limit=3)
    assert "3 others" in page


def test_a_non_member_is_starred():
    page = render(a_year(a_line(is_member=False)))
    assert "MERRICK   *" in page


def test_the_csv_has_a_row_for_each_producer():
    year = a_year(a_line(), a_line(party_code="HOLLIS", bushels=Decimal("40000")))
    rows = to_csv(year).strip().splitlines()
    assert len(rows) == 3
    assert rows[0].startswith("party_code,")


def test_the_csv_carries_the_share():
    year = a_year(
        a_line(bushels=Decimal("75000")),
        a_line(party_code="HOLLIS", bushels=Decimal("25000")),
    )
    assert "25.0000" in to_csv(year)


def test_combining_two_houses_adds_a_producer_up():
    first = a_year(a_line(), location_code="HOME")
    second = a_year(
        a_line(bushels=Decimal("6000"), charges=Decimal("500.00"), loads=4),
        location_code="LURAY",
    )
    rolled = combine([first, second])
    assert rolled.location_code == "ALL"
    assert rolled.bushels == Decimal("130000.00")
    assert rolled.lines[0].loads == 92


def test_combining_keeps_a_producer_who_is_only_at_one_house():
    first = a_year(a_line())
    second = a_year(a_line(party_code="HOLLIS"), location_code="LURAY")
    assert len(combine([first, second]).lines) == 2


def test_a_producer_who_is_a_member_at_one_house_only_is_not_a_member():
    first = a_year(a_line())
    second = a_year(a_line(is_member=False), location_code="LURAY")
    assert combine([first, second]).members() == ()


def test_the_combined_year_spans_both():
    first = Year(location_code="HOME", opens=OPENS, closes=date(2025, 3, 1))
    second = Year(location_code="LURAY", opens=date(2024, 7, 1), closes=CLOSES)
    rolled = combine([first, second])
    assert rolled.opens == date(2024, 7, 1)
    assert rolled.closes == CLOSES


def test_combining_nothing_is_refused():
    with pytest.raises(PatronageError):
        combine([])
