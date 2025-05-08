from dataclasses import replace
from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.ledger import standard_chart, trial_balance
from bushelworks.errors import ConflictError
from bushelworks.persistence.base import create_all, drop_all, engine_for, session_factory
from bushelworks.persistence.models import (
    JournalEntryRow,
    JournalLegRow,
    PeriodRow,
)
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


@pytest.fixture
def books(a_day):
    """The same day, with a chart and the two months around it on file."""
    with unit_of_work(a_day) as uow:
        uow.ledger.file_chart(standard_chart())
        uow.ledger.add_periods(
            [
                PeriodRow(
                    code="2024-09",
                    starts_on=date(2024, 9, 1),
                    ends_on=date(2024, 9, 30),
                    closed=True,
                    closed_on=date(2024, 10, 8),
                    closed_by="office",
                ),
                PeriodRow(
                    code="2024-10",
                    starts_on=date(2024, 10, 1),
                    ends_on=date(2024, 10, 31),
                ),
            ]
        )
        uow.commit()
    return a_day


def test_the_close_posts_the_days_settlements(books, rules):
    with unit_of_work(books) as uow:
        settle_producer(uow, "MERRICK", "HOME", rules, HARVEST, Decimal("4.35"))
        uow.commit()
    with unit_of_work(books) as uow:
        close = run_close(uow, HARVEST)
        uow.commit()
    assert close.is_clean
    assert len(close.entries_posted) == 1
    with unit_of_work(books) as uow:
        assert uow.ledger.is_posted("settlement", "S-2024-0001")


def test_the_books_balance_after_the_close(books, rules):
    with unit_of_work(books) as uow:
        settle_producer(uow, "MERRICK", "HOME", rules, HARVEST, Decimal("4.35"))
        uow.commit()
    with unit_of_work(books) as uow:
        run_close(uow, HARVEST)
        uow.commit()
    with unit_of_work(books) as uow:
        entries = uow.ledger.entries_between(HARVEST, HARVEST)
        balance = trial_balance(uow.ledger.chart(), entries, through=HARVEST)
    assert balance.balances
    assert balance.balance_of("2100") > Decimal("0")


def test_running_the_close_twice_does_not_post_twice(books, rules):
    with unit_of_work(books) as uow:
        settle_producer(uow, "MERRICK", "HOME", rules, HARVEST, Decimal("4.35"))
        uow.commit()
    for _ in range(2):
        with unit_of_work(books) as uow:
            close = run_close(uow, HARVEST)
            uow.commit()
    assert close.entries_posted == ()
    with unit_of_work(books) as uow:
        assert len(uow.ledger.for_source("settlement", "S-2024-0001")) == 1


def test_a_dry_run_writes_nothing(books, rules):
    with unit_of_work(books) as uow:
        settle_producer(uow, "MERRICK", "HOME", rules, HARVEST, Decimal("4.35"))
        uow.commit()
    with unit_of_work(books) as uow:
        close = run_close(uow, HARVEST, dry_run=True)
        uow.commit()
    assert close.is_clean
    assert close.entries_posted == ()
    with unit_of_work(books) as uow:
        assert not uow.ledger.is_posted("settlement", "S-2024-0001")


def test_a_closed_month_stops_the_close(books, rules):
    with unit_of_work(books) as uow:
        settle_producer(uow, "MERRICK", "HOME", rules, HARVEST, Decimal("4.35"))
        row = uow.ledger.period("2024-10")
        row.closed = True
        row.closed_on = date(2024, 11, 8)
        uow.commit()
    with unit_of_work(books) as uow:
        close = run_close(uow, HARVEST)
    assert not close.is_clean
    assert any(item.kind == "period" for item in close.discrepancies)


def test_a_house_with_no_periods_on_file_says_so(a_day, rules):
    with unit_of_work(a_day) as uow:
        uow.ledger.file_chart(standard_chart())
        settle_producer(uow, "MERRICK", "HOME", rules, HARVEST, Decimal("4.35"))
        uow.commit()
    with unit_of_work(a_day) as uow:
        close = run_close(uow, HARVEST)
    assert any("nothing to post into" in item.detail for item in close.discrepancies)


def test_a_settlement_that_will_not_reconcile_is_never_posted(books, rules):
    with unit_of_work(books) as uow:
        settle_producer(uow, "MERRICK", "HOME", rules, HARVEST, Decimal("4.35"))
        uow.commit()
    with unit_of_work(books) as uow:
        uow.settlements.row("S-2024-0001").net = Decimal("1.00")
        uow.commit()
    with unit_of_work(books) as uow:
        close = run_close(uow, HARVEST)
        uow.commit()
    assert close.entries_posted == ()
    with unit_of_work(books) as uow:
        assert not uow.ledger.is_posted("settlement", "S-2024-0001")


def test_a_hand_written_entry_that_does_not_balance_is_found(books):
    with unit_of_work(books) as uow:
        row = JournalEntryRow(
            number="J-BACKFILL", posted_on=HARVEST, memo="keyed by hand"
        )
        row.legs.append(
            JournalLegRow(
                sequence=1,
                account_code="1210",
                side="debit",
                dollars=Decimal("100.00"),
            )
        )
        row.legs.append(
            JournalLegRow(
                sequence=2,
                account_code="2100",
                side="credit",
                dollars=Decimal("90.00"),
            )
        )
        uow.session.add(row)
        uow.commit()
    with unit_of_work(books) as uow:
        close = run_close(uow, HARVEST)
    assert any(item.kind == "journal" for item in close.discrepancies)


def test_the_close_prints_how_many_entries_it_posted(books, rules):
    with unit_of_work(books) as uow:
        settle_producer(uow, "MERRICK", "HOME", rules, HARVEST, Decimal("4.35"))
        uow.commit()
    with unit_of_work(books) as uow:
        close = run_close(uow, HARVEST)
        uow.commit()
    assert "entries posted" in render(close)
