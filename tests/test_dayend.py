from dataclasses import replace
from datetime import date
from decimal import Decimal

import pytest

from bushelworks.errors import ConflictError
from bushelworks.persistence.base import create_all, drop_all, engine_for, session_factory
from bushelworks.persistence.unitofwork import unit_of_work
from bushelworks.services.dayend import render, require_clean, run_close
from bushelworks.services.scalehouse import record_load
from bushelworks.services.settlement_run import settle_producer

HARVEST = date(2024, 10, 3)


@pytest.fixture
def factory():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    yield session_factory(engine)
    drop_all(engine)


@pytest.fixture
def a_day(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        for number in ("A-10441", "A-10442", "A-10443"):
            record_load(
                uow, replace(corn_ticket, ticket_number=number), "MERRICK", rules
            )
        uow.commit()
    return factory


def test_an_empty_day_closes_clean(factory):
    with unit_of_work(factory) as uow:
        close = run_close(uow, HARVEST)
    assert close.is_clean
    assert close.tickets_written == 0
    assert close.bushels_in == Decimal("0.0000")


def test_the_close_counts_the_tickets(a_day):
    with unit_of_work(a_day) as uow:
        close = run_close(uow, HARVEST)
    assert close.tickets_written == 3
    assert close.tickets_voided == 0


def test_a_voided_ticket_is_counted_separately(a_day):
    with unit_of_work(a_day) as uow:
        uow.tickets.void("HOME", "A-10442", "reweighed")
        uow.commit()
    with unit_of_work(a_day) as uow:
        close = run_close(uow, HARVEST)
    assert close.tickets_written == 2
    assert close.tickets_voided == 1


def test_the_close_counts_the_bushels_that_came_in(a_day):
    with unit_of_work(a_day) as uow:
        close = run_close(uow, HARVEST)
    assert close.bushels_in == Decimal("2798.5714")


def test_nothing_went_out_on_a_day_with_no_shipments(a_day):
    with unit_of_work(a_day) as uow:
        close = run_close(uow, HARVEST)
    assert close.bushels_out == Decimal("0.0000")
    assert close.net_bushels == close.bushels_in


def test_the_close_counts_the_settlements(a_day, rules):
    with unit_of_work(a_day) as uow:
        settle_producer(uow, "MERRICK", "HOME", rules, HARVEST, Decimal("4.35"))
        uow.commit()
    with unit_of_work(a_day) as uow:
        close = run_close(uow, HARVEST)
    assert close.settlements_written == 1
    assert close.settled_value > Decimal("0")


def test_a_consecutive_run_of_tickets_closes_clean(a_day):
    with unit_of_work(a_day) as uow:
        close = run_close(uow, HARVEST)
    assert close.is_clean


def test_a_hole_in_the_ticket_numbers_is_found(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        for number in ("A-10441", "A-10444"):
            record_load(
                uow, replace(corn_ticket, ticket_number=number), "MERRICK", rules
            )
        uow.commit()
    with unit_of_work(factory) as uow:
        close = run_close(uow, HARVEST)
    assert not close.is_clean
    kinds = [item.kind for item in close.discrepancies]
    assert "ticket numbering" in kinds


def test_the_hole_names_the_missing_numbers(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        for number in ("A-10441", "A-10444"):
            record_load(
                uow, replace(corn_ticket, ticket_number=number), "MERRICK", rules
            )
        uow.commit()
    with unit_of_work(factory) as uow:
        close = run_close(uow, HARVEST)
    detail = close.discrepancies[0].detail
    assert "10442" in detail
    assert "10443" in detail


def test_one_ticket_cannot_have_a_hole(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
        uow.commit()
    with unit_of_work(factory) as uow:
        close = run_close(uow, HARVEST)
    assert close.is_clean


def test_a_settlement_that_does_not_balance_is_found(a_day, rules):
    with unit_of_work(a_day) as uow:
        settle_producer(uow, "MERRICK", "HOME", rules, HARVEST, Decimal("4.35"))
        uow.commit()
    with unit_of_work(a_day) as uow:
        row = uow.settlements.row("S-2024-0001")
        row.net = Decimal("1.00")
        uow.commit()
    with unit_of_work(a_day) as uow:
        close = run_close(uow, HARVEST)
    assert not close.is_clean
    assert any(item.kind == "settlement" for item in close.discrepancies)


def test_a_gross_that_does_not_match_its_lines_is_found(a_day, rules):
    with unit_of_work(a_day) as uow:
        settle_producer(uow, "MERRICK", "HOME", rules, HARVEST, Decimal("4.35"))
        uow.commit()
    with unit_of_work(a_day) as uow:
        row = uow.settlements.row("S-2024-0001")
        row.gross = Decimal("99.99")
        uow.commit()
    with unit_of_work(a_day) as uow:
        close = run_close(uow, HARVEST)
    assert any("does not match its lines" in item.detail for item in close.discrepancies)


def test_a_large_overnight_position_move_is_flagged(a_day):
    with unit_of_work(a_day) as uow:
        close = run_close(uow, HARVEST, position_tolerance=Decimal("100"))
    assert any(item.kind == "position" for item in close.discrepancies)


def test_a_quiet_night_is_not_flagged(a_day):
    with unit_of_work(a_day) as uow:
        close = run_close(uow, HARVEST, position_tolerance=Decimal("250000"))
    assert not any(item.kind == "position" for item in close.discrepancies)


def test_a_clean_close_can_be_signed_off(a_day):
    with unit_of_work(a_day) as uow:
        require_clean(run_close(uow, HARVEST))


def test_a_dirty_close_refuses_to_be_signed_off(a_day):
    with unit_of_work(a_day) as uow:
        close = run_close(uow, HARVEST, position_tolerance=Decimal("100"))
        with pytest.raises(ConflictError):
            require_clean(close)


def test_the_close_prints_for_the_day_book(a_day):
    with unit_of_work(a_day) as uow:
        text = render(run_close(uow, HARVEST))
    assert "DAILY CLOSE 2024-10-03" in text
    assert "tickets written" in text
    assert "closed clean" in text


def test_a_dirty_close_prints_its_problems(a_day):
    with unit_of_work(a_day) as uow:
        text = render(run_close(uow, HARVEST, position_tolerance=Decimal("100")))
    assert "problems:" in text
    assert "position" in text


def test_the_close_is_scoped_to_a_site(factory, rules, corn_ticket):
    with unit_of_work(factory) as uow:
        record_load(uow, corn_ticket, "MERRICK", rules)
        record_load(
            uow,
            replace(corn_ticket, ticket_number="A-20001", location_code="LURAY"),
            "MERRICK",
            rules,
        )
        uow.commit()
    with unit_of_work(factory) as uow:
        home = run_close(uow, HARVEST, location_code="HOME")
        luray = run_close(uow, HARVEST, location_code="LURAY")
    assert home.tickets_written == 1
    assert luray.tickets_written == 1
