"""Closing a month, and the three things that stop it.

The fixture posts one real settlement so the month has something in it. Every
test that wants a month to refuse breaks exactly one of the three checks, which
is the only way to know which check did the refusing.
"""

from dataclasses import replace
from datetime import date
from decimal import Decimal
from itertools import pairwise

import pytest

from bushelworks.domain.ledger import AccountType, standard_chart
from bushelworks.errors import ConflictError
from bushelworks.persistence.base import (
    create_all,
    drop_all,
    engine_for,
    session_factory,
)
from bushelworks.persistence.models import JournalEntryRow, JournalLegRow
from bushelworks.persistence.repositories.ledger import PeriodNotFound
from bushelworks.persistence.unitofwork import unit_of_work
from bushelworks.services.dayend import run_close
from bushelworks.services.monthend import (
    MonthEndError,
    close_period,
    earliest_open,
    file_year,
    margins_by_account,
    months_of,
    reopen_period,
    review,
)
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
def house(factory, rules, corn_ticket):
    """A chart, a filed year, three loads and one settlement, all posted.

    January through September are closed because they are empty and the house
    would have closed them as it went. Leaving them open makes every October
    test fail on the sequence check instead of on the thing it is testing.
    """
    with unit_of_work(factory) as uow:
        uow.ledger.file_chart(standard_chart())
        file_year(uow, 2024)
        for month in range(1, 10):
            close_period(uow, f"2024-{month:02d}", "office", on=date(2024, 10, 1))
        for number in ("A-10441", "A-10442", "A-10443"):
            record_load(
                uow, replace(corn_ticket, ticket_number=number), "MERRICK", rules
            )
        uow.commit()
    with unit_of_work(factory) as uow:
        settle_producer(uow, "MERRICK", "HOME", rules, HARVEST, Decimal("4.35"))
        uow.commit()
    with unit_of_work(factory) as uow:
        run_close(uow, HARVEST)
        uow.commit()
    return factory


class TestFilingAYear:
    def test_twelve_months_come_out(self) -> None:
        months = months_of(2025)
        assert len(months) == 12
        assert months[0] == ("2025-01", date(2025, 1, 1), date(2025, 1, 31))
        assert months[11] == ("2025-12", date(2025, 12, 1), date(2025, 12, 31))

    def test_february_of_a_leap_year_has_twenty_nine_days(self) -> None:
        code, starts, ends = months_of(2024)[1]
        assert code == "2024-02"
        assert ends == date(2024, 2, 29)

    def test_february_of_an_ordinary_year_has_twenty_eight(self) -> None:
        assert months_of(2025)[1][2] == date(2025, 2, 28)

    def test_every_month_runs_to_the_day_before_the_next(self) -> None:
        months = months_of(2025)
        for (_, _, ends), (_, starts, _) in pairwise(months):
            assert (starts - ends).days == 1

    def test_a_filed_year_is_on_file(self, factory) -> None:
        with unit_of_work(factory) as uow:
            file_year(uow, 2025)
            uow.commit()
        with unit_of_work(factory) as uow:
            assert len(uow.ledger.open_periods()) == 12

    def test_filing_the_same_year_twice_is_refused(self, factory) -> None:
        with unit_of_work(factory) as uow:
            file_year(uow, 2025)
            uow.commit()
        with unit_of_work(factory) as uow:
            with pytest.raises(ConflictError, match="already on file"):
                file_year(uow, 2025)

    def test_an_implausible_year_is_refused(self, factory) -> None:
        with unit_of_work(factory) as uow:
            with pytest.raises(MonthEndError, match="implausible"):
                file_year(uow, 1899)


class TestReview:
    def test_a_finished_month_reviews_clean(self, house) -> None:
        with unit_of_work(house) as uow:
            found = review(uow, "2024-10")
        assert found.is_clean
        assert found.settlements == 1
        assert found.entries == 1
        assert found.unposted == ()

    def test_the_month_carries_its_own_dates(self, house) -> None:
        with unit_of_work(house) as uow:
            found = review(uow, "2024-10")
        assert found.starts_on == date(2024, 10, 1)
        assert found.ends_on == date(2024, 10, 31)

    def test_a_settlement_that_never_posted_is_named(self, factory, rules, corn_ticket):
        with unit_of_work(factory) as uow:
            uow.ledger.file_chart(standard_chart())
            file_year(uow, 2024)
            record_load(uow, corn_ticket, "MERRICK", rules)
            uow.commit()
        with unit_of_work(factory) as uow:
            settle_producer(uow, "MERRICK", "HOME", rules, HARVEST, Decimal("4.35"))
            uow.commit()
        with unit_of_work(factory) as uow:
            found = review(uow, "2024-10")
        assert not found.is_clean
        assert found.unposted == ("S-2024-0001",)
        assert any(item.kind == "unposted" for item in found.outstanding)

    def test_an_earlier_open_month_stops_it(self, house) -> None:
        with unit_of_work(house) as uow:
            found = review(uow, "2024-11")
        assert any(item.kind == "sequence" for item in found.outstanding)
        assert "2024-10 is still open" in str(found.outstanding[0])

    def test_january_looks_at_the_december_before_it(self, factory) -> None:
        with unit_of_work(factory) as uow:
            uow.ledger.file_chart(standard_chart())
            file_year(uow, 2025)
            uow.commit()
        with unit_of_work(factory) as uow:
            found = review(uow, "2025-01")
        assert found.is_clean

    def test_a_journal_that_does_not_balance_is_found(self, house) -> None:
        with unit_of_work(house) as uow:
            row = JournalEntryRow(
                number="J-HAND", posted_on=date(2024, 10, 20), memo="keyed by hand"
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
                    dollars=Decimal("75.00"),
                )
            )
            uow.session.add(row)
            uow.commit()
        with unit_of_work(house) as uow:
            found = review(uow, "2024-10")
        assert any(item.kind == "journal" for item in found.outstanding)

    def test_a_month_nobody_filed_is_a_lookup_failure(self, house) -> None:
        with unit_of_work(house) as uow:
            with pytest.raises(PeriodNotFound):
                review(uow, "2029-04")


class TestClosing:
    def test_a_finished_month_closes(self, house) -> None:
        with unit_of_work(house) as uow:
            found = close_period(uow, "2024-10", "R. Merrick", on=date(2024, 11, 6))
            uow.commit()
        assert found.closed
        assert found.is_clean
        with unit_of_work(house) as uow:
            row = uow.ledger.period("2024-10")
            assert row.closed
            assert row.closed_by == "R. Merrick"
            assert row.closed_on == date(2024, 11, 6)

    def test_a_closed_month_is_no_longer_open(self, house) -> None:
        with unit_of_work(house) as uow:
            close_period(uow, "2024-10", "office", on=date(2024, 11, 6))
            uow.commit()
        with unit_of_work(house) as uow:
            assert "2024-10" not in [p.code for p in uow.ledger.open_periods()]

    def test_closing_twice_is_refused(self, house) -> None:
        with unit_of_work(house) as uow:
            close_period(uow, "2024-10", "office", on=date(2024, 11, 6))
            uow.commit()
        with unit_of_work(house) as uow:
            with pytest.raises(ConflictError, match="already closed"):
                close_period(uow, "2024-10", "office")

    def test_a_month_with_something_outstanding_will_not_close(self, house) -> None:
        with unit_of_work(house) as uow:
            with pytest.raises(ConflictError, match="2024-11 will not close"):
                close_period(uow, "2024-11", "office")

    def test_force_closes_it_anyway(self, house) -> None:
        with unit_of_work(house) as uow:
            found = close_period(
                uow, "2024-11", "office", on=date(2024, 12, 3), force=True
            )
            uow.commit()
        assert found.closed
        assert not found.is_clean
        with unit_of_work(house) as uow:
            assert uow.ledger.period("2024-11").closed

    def test_nothing_can_be_posted_into_a_closed_month(self, house, rules) -> None:
        with unit_of_work(house) as uow:
            close_period(uow, "2024-10", "office", on=date(2024, 11, 6))
            uow.commit()
        with unit_of_work(house) as uow:
            close = run_close(uow, HARVEST)
        assert any(item.kind == "period" for item in close.discrepancies)


class TestReopening:
    def test_a_closed_month_can_be_opened_again_with_a_reason(self, house) -> None:
        with unit_of_work(house) as uow:
            close_period(uow, "2024-10", "office", on=date(2024, 11, 6))
            uow.commit()
        with unit_of_work(house) as uow:
            row = reopen_period(uow, "2024-10", "the co-op amended its return")
            uow.commit()
            assert not row.closed
            assert row.closed_on is None
            assert row.closed_by == ""

    def test_reopening_without_a_reason_is_refused(self, house) -> None:
        with unit_of_work(house) as uow:
            close_period(uow, "2024-10", "office", on=date(2024, 11, 6))
            uow.commit()
        with unit_of_work(house) as uow:
            with pytest.raises(MonthEndError, match="needs a reason"):
                reopen_period(uow, "2024-10", "   ")

    def test_a_month_that_is_open_cannot_be_reopened(self, house) -> None:
        with unit_of_work(house) as uow:
            with pytest.raises(ConflictError, match="is not closed"):
                reopen_period(uow, "2024-10", "no reason to")

    def test_a_reopened_month_takes_postings_again(self, house) -> None:
        with unit_of_work(house) as uow:
            close_period(uow, "2024-10", "office", on=date(2024, 11, 6))
            uow.commit()
        with unit_of_work(house) as uow:
            reopen_period(uow, "2024-10", "a load was keyed late")
            uow.commit()
        with unit_of_work(house) as uow:
            close = run_close(uow, HARVEST)
        assert not any(item.kind == "period" for item in close.discrepancies)


class TestWhereALateTicketGoes:
    def test_the_earliest_open_month_is_the_one(self, house) -> None:
        with unit_of_work(house) as uow:
            assert earliest_open(uow).code == "2024-10"

    def test_it_moves_on_as_months_close(self, house) -> None:
        with unit_of_work(house) as uow:
            close_period(uow, "2024-10", "office", on=date(2024, 11, 6))
            uow.commit()
        with unit_of_work(house) as uow:
            assert earliest_open(uow).code == "2024-11"

    def test_a_house_with_no_periods_has_nowhere_to_put_it(self, factory) -> None:
        with unit_of_work(factory) as uow:
            assert earliest_open(uow) is None


class TestMargin:
    def test_the_month_carries_what_it_earned(self, house) -> None:
        with unit_of_work(house) as uow:
            found = review(uow, "2024-10")
        assert found.revenue > Decimal("0")
        assert found.margin == found.revenue - found.expense

    def test_a_month_with_no_balance_reads_as_nothing(self) -> None:
        from bushelworks.services.monthend import MonthEnd

        empty = MonthEnd(code="2024-01", starts_on=HARVEST, ends_on=HARVEST)
        assert empty.revenue == Decimal("0.00")
        assert empty.expense == Decimal("0.00")
        assert empty.margin == Decimal("0.00")

    def test_the_revenue_accounts_come_back_by_code(self, house) -> None:
        with unit_of_work(house) as uow:
            margins = margins_by_account(uow, "2024-10")
        assert "4100" in margins
        assert "4000" in margins
        assert "1210" not in margins

    def test_the_heading_carries_what_its_accounts_earned(self, house) -> None:
        with unit_of_work(house) as uow:
            margins = margins_by_account(uow, "2024-10")
            chart = uow.ledger.chart()
        leaves = sum(
            margins[a.code]
            for a in chart.of_type(AccountType.REVENUE)
            if not chart.is_heading(a.code)
        )
        assert margins["4000"] == leaves
