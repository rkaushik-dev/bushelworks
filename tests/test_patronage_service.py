"""The patronage table, built off settlements that are actually on file."""

from dataclasses import replace
from datetime import date
from decimal import Decimal

import pytest

from bushelworks.persistence.base import create_all, drop_all, engine_for, session_factory
from bushelworks.persistence.unitofwork import unit_of_work
from bushelworks.reports.patronage import PatronageError
from bushelworks.services.houses import house_rulebook
from bushelworks.services.parties import open_account
from bushelworks.services.patronage import (
    MARGIN_KINDS,
    allocate,
    build_crop_year,
    build_year,
    crop_year,
    top_producers,
)
from bushelworks.services.scalehouse import record_load
from bushelworks.services.settlement_run import settle_producer

OPENS = date(2024, 9, 1)
CLOSES = date(2025, 8, 31)
SETTLED = date(2024, 10, 20)
SPOT = Decimal("4.3500")


@pytest.fixture
def engine():
    eng = engine_for("sqlite+pysqlite:///:memory:")
    create_all(eng)
    yield eng
    drop_all(eng)


@pytest.fixture
def factory(engine):
    return session_factory(engine)


def settle(factory, rules, corn_ticket, party_code, count, start, settled=SETTLED):
    with unit_of_work(factory) as uow:
        open_account(uow, code=party_code, name=f"{party_code.title()} Farms")
        for index in range(count):
            record_load(
                uow,
                replace(corn_ticket, ticket_number=f"A-{start + index}"),
                party_code,
                rules,
            )
        settle_producer(uow, party_code, "HOME", house_rulebook(), settled, SPOT)
        uow.commit()


@pytest.fixture
def one_producer(factory, rules, corn_ticket):
    settle(factory, rules, corn_ticket, "MERRICK", 3, 10441)
    return factory


@pytest.fixture
def two_producers(factory, rules, corn_ticket):
    settle(factory, rules, corn_ticket, "MERRICK", 3, 10441)
    settle(factory, rules, corn_ticket, "HOLLIS", 1, 20441)
    return factory


def year_of(factory, **kwargs):
    with unit_of_work(factory) as uow:
        return build_year(uow, OPENS, CLOSES, **kwargs)


def test_a_house_with_no_settlements_has_an_empty_year(factory):
    year = year_of(factory)
    assert year.lines == ()
    assert year.bushels == Decimal("0.0000")


def test_a_settled_producer_appears_once(one_producer):
    year = year_of(one_producer)
    assert [line.party_code for line in year.lines] == ["MERRICK"]


def test_the_producer_carries_the_name_off_the_party_table(one_producer):
    assert year_of(one_producer).lines[0].name == "Merrick Farms"


def test_a_producer_with_no_party_row_falls_back_to_the_code(
    factory, rules, corn_ticket
):
    with unit_of_work(factory) as uow:
        for index in range(2):
            record_load(
                uow,
                replace(corn_ticket, ticket_number=f"A-{30441 + index}"),
                "GHOST",
                rules,
            )
        settle_producer(uow, "GHOST", "HOME", house_rulebook(), SETTLED, SPOT)
        uow.commit()
    assert year_of(factory).lines[0].name == "GHOST"


def test_the_bushels_come_off_the_gross_lines(one_producer):
    year = year_of(one_producer)
    assert year.lines[0].bushels > Decimal("2000")


def test_the_gross_is_the_settlement_gross(one_producer):
    year = year_of(one_producer)
    assert year.lines[0].gross > Decimal("0")


def test_the_margin_is_only_the_charge_kinds(one_producer):
    line = year_of(one_producer).lines[0]
    assert Decimal("0") < line.margin < line.gross


def test_the_load_count_comes_off_the_tickets(one_producer):
    assert year_of(one_producer).lines[0].loads == 3


def test_two_producers_both_land_on_the_table(two_producers):
    assert [line.party_code for line in year_of(two_producers).lines] == [
        "HOLLIS",
        "MERRICK",
    ]


def test_the_bigger_producer_ranks_first(two_producers):
    assert year_of(two_producers).by_bushels()[0].party_code == "MERRICK"


def test_the_year_totals_across_producers(two_producers):
    year = year_of(two_producers)
    assert year.bushels == sum(line.bushels for line in year.lines)


def test_everybody_is_a_member_when_no_roll_is_given(two_producers):
    assert len(year_of(two_producers).members()) == 2


def test_a_roll_leaves_the_others_off_the_member_list(two_producers):
    year = year_of(two_producers, members=["MERRICK"])
    assert [line.party_code for line in year.members()] == ["MERRICK"]


def test_an_empty_roll_means_nobody_is_a_member(two_producers):
    assert year_of(two_producers, members=[]).members() == ()


def test_a_settlement_outside_the_span_is_left_out(factory, rules, corn_ticket):
    settle(factory, rules, corn_ticket, "MERRICK", 2, 10441, settled=date(2024, 8, 1))
    assert year_of(factory).lines == ()


def test_a_settlement_at_another_site_is_left_out(one_producer):
    with unit_of_work(one_producer) as uow:
        year = build_year(uow, OPENS, CLOSES, location_code="LURAY")
    assert year.lines == ()


def test_the_site_is_stamped_on_the_year(one_producer):
    with unit_of_work(one_producer) as uow:
        assert build_year(uow, OPENS, CLOSES, location_code="HOME").location_code == "HOME"


def test_no_site_means_every_site(one_producer):
    assert year_of(one_producer).location_code == "*"


def test_a_voided_settlement_drops_out(one_producer):
    with unit_of_work(one_producer) as uow:
        for row in uow.settlements.for_day(SETTLED):
            row.voided = True
        uow.commit()
    assert year_of(one_producer).lines == ()


def test_a_year_that_closes_before_it_opens_is_refused(factory):
    with unit_of_work(factory) as uow, pytest.raises(PatronageError):
        build_year(uow, CLOSES, OPENS)


def test_the_crop_year_runs_september_to_august():
    assert crop_year(2024) == (date(2024, 9, 1), date(2025, 8, 31))


def test_a_crop_year_picks_up_an_october_settlement(one_producer):
    with unit_of_work(one_producer) as uow:
        year = build_crop_year(uow, 2024)
    assert len(year.lines) == 1


def test_a_crop_year_leaves_out_the_year_before(one_producer):
    with unit_of_work(one_producer) as uow:
        year = build_crop_year(uow, 2023)
    assert year.lines == ()


def test_the_top_list_is_biggest_first(two_producers):
    assert top_producers(year_of(two_producers)) == ("MERRICK", "HOLLIS")


def test_the_top_list_can_be_cut_short(two_producers):
    assert top_producers(year_of(two_producers), count=1) == ("MERRICK",)


def test_an_allocation_splits_the_pool(two_producers):
    split = allocate(year_of(two_producers), Decimal("10000.00"))
    assert sum(split.values()) == Decimal("10000.00")


def test_a_share_under_the_minimum_is_dropped(two_producers):
    split = allocate(year_of(two_producers), Decimal("10.00"), minimum=Decimal("9.00"))
    assert "HOLLIS" not in split


def test_dropping_a_share_does_not_hand_it_to_somebody_else(two_producers):
    year = year_of(two_producers)
    whole = year.allocations(Decimal("1000.00"))
    trimmed = allocate(year, Decimal("1000.00"), minimum=Decimal("300.00"))
    assert "HOLLIS" not in trimmed
    assert trimmed["MERRICK"] == whole["MERRICK"]


def test_the_margin_kinds_leave_out_the_checkoff():
    assert "assessment" not in {kind.value for kind in MARGIN_KINDS}


def test_the_margin_kinds_leave_out_advances():
    assert "advance" not in {kind.value for kind in MARGIN_KINDS}
