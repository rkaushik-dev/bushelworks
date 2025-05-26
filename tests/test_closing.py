"""Closing a year into equity.

The check that matters is the one at the bottom of TestTheEntry: run the trial
balance again with the closing entry in it and every revenue and expense account
has to read zero. Everything else here is about which equity account the money
lands in, which is the part houses disagree about.
"""

from dataclasses import replace
from datetime import date
from decimal import Decimal

import pytest
from click.testing import CliRunner

from bushelworks.cli.main import cli
from bushelworks.domain.ledger import (
    Account,
    AccountType,
    ChartOfAccounts,
    JournalEntry,
    Side,
    TrialBalance,
    credit,
    debit,
    standard_chart,
    trial_balance,
)
from bushelworks.errors import ConflictError
from bushelworks.persistence.base import create_all, engine_for, session_factory
from bushelworks.persistence.unitofwork import unit_of_work
from bushelworks.services.closing import (
    RETAINED_EARNINGS,
    SOURCE,
    ClosingError,
    accounts_left_open,
    close_year,
    closing_entry,
    opening_balances,
    render,
    review_year,
)
from bushelworks.services.dayend import run_close
from bushelworks.services.monthend import close_period, file_year
from bushelworks.services.scalehouse import record_load
from bushelworks.services.settlement_run import settle_producer

HARVEST = date(2024, 10, 3)
NEW_YEAR = date(2024, 12, 31)


def a_year(chart: ChartOfAccounts) -> TrialBalance:
    """Storage and drying earned, dryer fuel spent, on one trial balance."""
    journal = [
        JournalEntry(
            number="J-1",
            posted_on=HARVEST,
            memo="settlement",
            legs=(
                debit("1210", Decimal("4350.00")),
                credit("4100", Decimal("400.00")),
                credit("4200", Decimal("600.00")),
                credit("2100", Decimal("3350.00")),
            ),
        ),
        JournalEntry(
            number="J-2",
            posted_on=date(2024, 11, 4),
            memo="gas bill",
            legs=(
                debit("5200", Decimal("250.00")),
                credit("1100", Decimal("250.00")),
            ),
        ),
    ]
    return trial_balance(chart, journal, through=NEW_YEAR, since=date(2024, 1, 1))


@pytest.fixture
def chart() -> ChartOfAccounts:
    return standard_chart()


@pytest.fixture
def balance(chart):
    return a_year(chart)


class TestTheEntry:
    def test_it_balances(self, chart, balance) -> None:
        closing_entry(chart, balance, "Y-1", NEW_YEAR).check_balances()

    def test_every_revenue_account_is_debited_for_what_it_holds(
        self, chart, balance
    ) -> None:
        entry = closing_entry(chart, balance, "Y-1", NEW_YEAR)
        storage = entry.touching("4100")[0]
        assert storage.side is Side.DEBIT
        assert storage.dollars == Decimal("400.00")

    def test_every_expense_account_is_credited(self, chart, balance) -> None:
        entry = closing_entry(chart, balance, "Y-1", NEW_YEAR)
        fuel = entry.touching("5200")[0]
        assert fuel.side is Side.CREDIT
        assert fuel.dollars == Decimal("250.00")

    def test_the_margin_reaches_retained_earnings(self, chart, balance) -> None:
        entry = closing_entry(chart, balance, "Y-1", NEW_YEAR)
        retained = entry.touching(RETAINED_EARNINGS)[0]
        assert retained.side is Side.CREDIT
        assert retained.dollars == Decimal("750.00")  # 1000 earned less 250 spent

    def test_an_account_with_nothing_in_it_gets_no_leg(self, chart, balance) -> None:
        entry = closing_entry(chart, balance, "Y-1", NEW_YEAR)
        assert entry.touching("4500") == ()

    def test_a_balance_sheet_account_is_left_alone(self, chart, balance) -> None:
        entry = closing_entry(chart, balance, "Y-1", NEW_YEAR)
        assert entry.touching("1210") == ()
        assert entry.touching("2100") == ()

    def test_it_points_at_the_year_it_closed(self, chart, balance) -> None:
        entry = closing_entry(chart, balance, "Y-1", NEW_YEAR)
        assert entry.source == SOURCE
        assert entry.source_reference == "2024"

    def test_a_loss_debits_retained_earnings(self, chart) -> None:
        journal = [
            JournalEntry(
                number="J-1",
                posted_on=HARVEST,
                memo="a bad year",
                legs=(
                    debit("5200", Decimal("900.00")),
                    credit("4100", Decimal("100.00")),
                    credit("1100", Decimal("800.00")),
                ),
            )
        ]
        balance = trial_balance(chart, journal, through=NEW_YEAR)
        entry = closing_entry(chart, balance, "Y-1", NEW_YEAR)
        retained = entry.touching(RETAINED_EARNINGS)[0]
        assert retained.side is Side.DEBIT
        assert retained.dollars == Decimal("800.00")
        entry.check_balances()

    def test_a_contra_revenue_balance_closes_the_other_way(self, chart) -> None:
        """More reversed than sold, so storage revenue sits on the debit side."""
        journal = [
            JournalEntry(
                number="J-1",
                posted_on=HARVEST,
                memo="reversals",
                legs=(
                    debit("4100", Decimal("60.00")),
                    credit("1100", Decimal("60.00")),
                ),
            )
        ]
        balance = trial_balance(chart, journal, through=NEW_YEAR)
        assert balance.balance_of("4100") == Decimal("-60.00")
        entry = closing_entry(chart, balance, "Y-1", NEW_YEAR)
        storage = entry.touching("4100")[0]
        assert storage.side is Side.CREDIT
        assert storage.dollars == Decimal("60.00")
        entry.check_balances()

    def test_a_year_with_nothing_in_it_will_not_close(self, chart) -> None:
        empty = trial_balance(chart, [], through=NEW_YEAR)
        with pytest.raises(ClosingError, match="nothing to close"):
            closing_entry(chart, empty, "Y-1", NEW_YEAR)

    def test_a_retained_account_that_is_a_heading_is_refused(
        self, chart, balance
    ) -> None:
        with pytest.raises(Exception, match="post to one of"):
            closing_entry(chart, balance, "Y-1", NEW_YEAR, retained="3000")

    def test_the_accounts_read_zero_afterwards(self, chart, balance) -> None:
        """The whole point, checked by running the books again with it in."""
        entry = closing_entry(chart, balance, "Y-1", NEW_YEAR)
        journal = [
            JournalEntry(
                number="J-1",
                posted_on=HARVEST,
                memo="settlement",
                legs=(
                    debit("1210", Decimal("4350.00")),
                    credit("4100", Decimal("400.00")),
                    credit("4200", Decimal("600.00")),
                    credit("2100", Decimal("3350.00")),
                ),
            ),
            JournalEntry(
                number="J-2",
                posted_on=date(2024, 11, 4),
                memo="gas bill",
                legs=(
                    debit("5200", Decimal("250.00")),
                    credit("1100", Decimal("250.00")),
                ),
            ),
            entry,
        ]
        after = trial_balance(chart, journal, through=NEW_YEAR)
        assert after.balances
        assert accounts_left_open(chart, after) == ()
        assert after.balance_of(RETAINED_EARNINGS) == Decimal("750.00")
        assert after.subtotal(AccountType.REVENUE) == Decimal("0.00")
        assert after.subtotal(AccountType.EXPENSE) == Decimal("0.00")


class TestClosesTo:
    @pytest.fixture
    def co_op(self) -> ChartOfAccounts:
        """A chart where storage margin goes to a patronage account."""
        chart = standard_chart()
        chart.add(
            Account("3200", "Patronage to allocate", AccountType.EQUITY, parent="3000")
        )
        return chart

    def test_an_account_can_name_where_it_closes(self, co_op) -> None:
        directed = ChartOfAccounts(
            [
                a if a.code != "4100" else replace(a, closes_to="3200")
                for a in co_op
            ]
        )
        entry = closing_entry(directed, a_year(directed), "Y-1", NEW_YEAR)
        patronage = entry.touching("3200")[0]
        assert patronage.side is Side.CREDIT
        assert patronage.dollars == Decimal("400.00")

    def test_what_is_left_still_goes_to_retained_earnings(self, co_op) -> None:
        directed = ChartOfAccounts(
            [
                a if a.code != "4100" else replace(a, closes_to="3200")
                for a in co_op
            ]
        )
        entry = closing_entry(directed, a_year(directed), "Y-1", NEW_YEAR)
        retained = entry.touching(RETAINED_EARNINGS)[0]
        assert retained.dollars == Decimal("350.00")  # 600 drying less 250 fuel
        entry.check_balances()

    def test_a_heading_passes_its_destination_down(self, co_op) -> None:
        """Set it on 4000 and every revenue account under it follows."""
        directed = ChartOfAccounts(
            [
                a if a.code != "4000" else replace(a, closes_to="3200")
                for a in co_op
            ]
        )
        entry = closing_entry(directed, a_year(directed), "Y-1", NEW_YEAR)
        patronage = entry.touching("3200")[0]
        assert patronage.dollars == Decimal("1000.00")  # both revenue accounts
        retained = entry.touching(RETAINED_EARNINGS)[0]
        assert retained.side is Side.DEBIT
        assert retained.dollars == Decimal("250.00")  # only the fuel

    def test_the_nearest_setting_wins(self, co_op) -> None:
        directed = ChartOfAccounts(
            [
                {
                    "4000": replace(a, closes_to="3200"),
                    "4100": replace(a, closes_to=RETAINED_EARNINGS),
                }.get(a.code, a)
                for a in co_op
            ]
        )
        entry = closing_entry(directed, a_year(directed), "Y-1", NEW_YEAR)
        assert entry.touching("3200")[0].dollars == Decimal("600.00")


@pytest.fixture
def factory():
    engine = engine_for("sqlite+pysqlite:///:memory:")
    create_all(engine)
    return session_factory(engine)


@pytest.fixture
def house(factory, rules, corn_ticket):
    """A finished 2024: a chart, twelve closed months and one posted settlement."""
    with unit_of_work(factory) as uow:
        uow.ledger.file_chart(standard_chart())
        file_year(uow, 2024)
        for month in range(1, 10):
            close_period(uow, f"2024-{month:02d}", "office", on=date(2024, 10, 1))
        for number in ("A-10441", "A-10442"):
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
    with unit_of_work(factory) as uow:
        for month in (10, 11, 12):
            close_period(uow, f"2024-{month:02d}", "office", on=date(2025, 1, 6))
        uow.commit()
    return factory


class TestReview:
    def test_a_finished_year_reviews_clean(self, house) -> None:
        with unit_of_work(house) as uow:
            found = review_year(uow, 2024)
        assert found.is_clean
        assert found.entry is not None
        assert found.revenue > Decimal("0")
        assert found.margin == found.revenue - found.expense

    def test_an_open_month_stops_it(self, factory, house) -> None:
        with unit_of_work(house) as uow:
            uow.ledger.period("2024-12").closed = False
            uow.commit()
        with unit_of_work(house) as uow:
            found = review_year(uow, 2024)
        assert not found.is_clean
        assert "2024-12" in found.outstanding[0]

    def test_a_year_nobody_filed_is_reported_month_by_month(self, factory) -> None:
        with unit_of_work(factory) as uow:
            uow.ledger.file_chart(standard_chart())
            uow.commit()
        with unit_of_work(factory) as uow:
            found = review_year(uow, 2024)
        assert not found.is_clean
        assert any("not on file" in item for item in found.outstanding)

    def test_an_implausible_year_is_refused(self, house) -> None:
        with unit_of_work(house) as uow:
            with pytest.raises(ClosingError, match="implausible"):
                review_year(uow, 1899)

    def test_the_destinations_are_named(self, house) -> None:
        with unit_of_work(house) as uow:
            found = review_year(uow, 2024)
        assert [d.account_code for d in found.destinations] == [RETAINED_EARNINGS]
        assert found.destinations[0].dollars == found.margin


class TestClosing:
    def test_the_year_closes(self, house) -> None:
        with unit_of_work(house) as uow:
            found = close_year(uow, 2024, "R. Merrick")
            uow.commit()
        assert found.closed
        with unit_of_work(house) as uow:
            assert uow.ledger.is_posted(SOURCE, "2024")

    def test_the_accounts_read_zero_after_it(self, house) -> None:
        with unit_of_work(house) as uow:
            close_year(uow, 2024, "office")
            uow.commit()
        with unit_of_work(house) as uow:
            chart = uow.ledger.chart()
            entries = uow.ledger.entries_between(date(2024, 1, 1), date(2024, 12, 31))
            after = trial_balance(chart, entries, through=date(2024, 12, 31))
            assert accounts_left_open(chart, after) == ()
            assert after.balances

    def test_closing_twice_is_refused(self, house) -> None:
        with unit_of_work(house) as uow:
            close_year(uow, 2024, "office")
            uow.commit()
        with unit_of_work(house) as uow:
            with pytest.raises(ConflictError, match="already been closed"):
                close_year(uow, 2024, "office")

    def test_a_year_with_an_open_month_will_not_close(self, house) -> None:
        with unit_of_work(house) as uow:
            uow.ledger.period("2024-11").closed = False
            uow.commit()
        with unit_of_work(house) as uow:
            with pytest.raises(ConflictError, match="will not close"):
                close_year(uow, 2024, "office")

    def test_force_closes_it_anyway(self, house) -> None:
        with unit_of_work(house) as uow:
            uow.ledger.period("2024-11").closed = False
            uow.commit()
        with unit_of_work(house) as uow:
            found = close_year(uow, 2024, "office", force=True)
            uow.commit()
        assert found.closed
        assert not found.is_clean


class TestOpeningBalances:
    def test_the_balance_sheet_accounts_carry_forward(self, house) -> None:
        with unit_of_work(house) as uow:
            close_year(uow, 2024, "office")
            uow.commit()
        with unit_of_work(house) as uow:
            carried = opening_balances(uow, 2025)
        assert "1210" in carried
        assert carried[RETAINED_EARNINGS] > Decimal("0")

    def test_revenue_and_expense_are_not_carried(self, house) -> None:
        with unit_of_work(house) as uow:
            close_year(uow, 2024, "office")
            uow.commit()
        with unit_of_work(house) as uow:
            carried = opening_balances(uow, 2025)
        assert not any(code.startswith(("4", "5")) for code in carried)

    def test_retained_earnings_is_always_present(self, factory) -> None:
        with unit_of_work(factory) as uow:
            uow.ledger.file_chart(standard_chart())
            uow.commit()
        with unit_of_work(factory) as uow:
            assert opening_balances(uow, 2025) == {RETAINED_EARNINGS: Decimal("0.00")}


class TestRender:
    def test_it_names_the_year_and_the_margin(self, house) -> None:
        with unit_of_work(house) as uow:
            page = render(review_year(uow, 2024))
        assert "YEAR END 2024" in page
        assert "margin" in page
        assert "would close clean" in page

    def test_a_closed_year_says_so(self, house) -> None:
        with unit_of_work(house) as uow:
            found = close_year(uow, 2024, "office")
            page = render(found)
        assert "closed" in page

    def test_what_is_outstanding_is_listed(self, house) -> None:
        with unit_of_work(house) as uow:
            uow.ledger.period("2024-11").closed = False
            uow.commit()
        with unit_of_work(house) as uow:
            page = render(review_year(uow, 2024))
        assert "outstanding" in page
        assert "2024-11" in page


class TestAccountsLeftOpen:
    def test_it_finds_one_that_was_missed(self, chart, balance) -> None:
        assert set(accounts_left_open(chart, balance)) == {"4100", "4200", "5200"}

    def test_it_can_be_narrowed_to_the_ones_asked_about(self, chart, balance) -> None:
        assert accounts_left_open(chart, balance, ["5200"]) == ("5200",)

    def test_a_closed_set_is_empty(self, chart) -> None:
        assert accounts_left_open(chart, trial_balance(chart, [], through=NEW_YEAR)) == ()


class TestCommands:
    @pytest.fixture
    def on_disk(self, tmp_path, rules, corn_ticket, monkeypatch):
        """The same finished year, in a database the CLI can open by url."""
        url = f"sqlite+pysqlite:///{tmp_path / 'house.sqlite3'}"
        engine = engine_for(url)
        create_all(engine)
        factory = session_factory(engine)
        with unit_of_work(factory) as uow:
            uow.ledger.file_chart(standard_chart())
            file_year(uow, 2024)
            for month in range(1, 10):
                close_period(uow, f"2024-{month:02d}", "office", on=date(2024, 10, 1))
            for number in ("A-10441", "A-10442"):
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
        with unit_of_work(factory) as uow:
            for month in (10, 11, 12):
                close_period(uow, f"2024-{month:02d}", "office", on=date(2025, 1, 6))
            uow.commit()
        monkeypatch.setenv("BUSHELWORKS_DATABASE_URL", url)
        return url

    @pytest.fixture
    def runner(self):
        return CliRunner()

    def test_reviewing_the_year_writes_nothing(self, runner, on_disk) -> None:
        found = runner.invoke(cli, ["year-end", "2024"], catch_exceptions=False)
        assert found.exit_code == 0
        assert "would close clean" in found.output
        again = runner.invoke(cli, ["year-end", "2024"], catch_exceptions=False)
        assert "would close clean" in again.output

    def test_the_year_closes(self, runner, on_disk) -> None:
        found = runner.invoke(
            cli, ["close-year", "2024", "--by", "office"], catch_exceptions=False
        )
        assert found.exit_code == 0
        assert "YEAR END 2024" in found.output
        after = runner.invoke(cli, ["year-end", "2024"], catch_exceptions=False)
        assert "already been closed" in after.output

    def test_closing_twice_is_refused(self, runner, on_disk) -> None:
        runner.invoke(
            cli, ["close-year", "2024", "--by", "office"], catch_exceptions=False
        )
        found = runner.invoke(cli, ["close-year", "2024", "--by", "office"])
        assert found.exit_code != 0

    def test_the_books_read_zero_after_the_command(self, runner, on_disk) -> None:
        runner.invoke(
            cli, ["close-year", "2024", "--by", "office"], catch_exceptions=False
        )
        found = runner.invoke(
            cli, ["income", "2024-01-01", "2024-12-31"], catch_exceptions=False
        )
        assert "0.00" in found.output

    def test_it_can_be_told_a_different_equity_account(self, runner, on_disk) -> None:
        found = runner.invoke(
            cli,
            ["close-year", "2024", "--by", "office", "--retained", "3000"],
        )
        assert found.exit_code != 0  # 3000 is a heading
