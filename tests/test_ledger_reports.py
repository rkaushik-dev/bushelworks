"""The three printed pages and the CSV, plus the commands that print them.

Widths are checked because these go to an eighty column printer and a line that
runs over wraps into the next one, which the office reads as a missing account.
The repository has already shipped that once on the statement.
"""

from dataclasses import replace
from datetime import date
from decimal import Decimal

import pytest
from click.testing import CliRunner

from bushelworks.cli.main import cli
from bushelworks.domain.ledger import (
    JournalEntry,
    credit,
    debit,
    standard_chart,
    trial_balance,
)
from bushelworks.persistence.base import create_all, engine_for, session_factory
from bushelworks.persistence.unitofwork import unit_of_work
from bushelworks.reports.ledger import (
    WIDTH,
    balance_sheet,
    income_statement,
    to_csv,
    trial_balance_page,
)
from bushelworks.services.dayend import run_close
from bushelworks.services.monthend import close_period, file_year
from bushelworks.services.scalehouse import record_load
from bushelworks.services.settlement_run import settle_producer

HARVEST = date(2024, 10, 3)
OCTOBER = date(2024, 10, 31)


@pytest.fixture
def chart():
    return standard_chart()


@pytest.fixture
def balance(chart):
    """One settlement and the cheque that paid it, posted."""
    journal = [
        JournalEntry(
            number="J-202410-0001",
            posted_on=HARVEST,
            memo="settlement S-2024-0001",
            legs=(
                debit("1210", Decimal("4350.00")),
                credit("4100", Decimal("42.30")),
                credit("4200", Decimal("118.75")),
                credit("2100", Decimal("4188.95")),
            ),
            source="settlement",
            source_reference="S-2024-0001",
        ),
        JournalEntry(
            number="J-202410-0002",
            posted_on=date(2024, 10, 20),
            memo="cheque run",
            legs=(
                debit("2100", Decimal("4188.95")),
                credit("1100", Decimal("4188.95")),
            ),
            source="payment",
            source_reference="C-2024-0100",
        ),
    ]
    return trial_balance(chart, journal, through=OCTOBER, since=date(2024, 10, 1))


class TestTrialBalancePage:
    def test_it_fits_eighty_columns(self, balance) -> None:
        for line in trial_balance_page(balance).split("\n"):
            assert len(line) <= WIDTH, line

    def test_the_totals_agree(self, balance) -> None:
        page = trial_balance_page(balance)
        assert "4,350.00" in page
        assert "OUT OF BALANCE" not in page

    def test_the_accounts_that_were_touched_are_on_it(self, balance) -> None:
        page = trial_balance_page(balance)
        for name in ("Grain inventory, company owned", "Storage revenue"):
            assert name in page

    def test_the_untouched_ones_are_not(self, balance) -> None:
        assert "Demurrage recovered" not in trial_balance_page(balance)

    def test_the_auditor_can_ask_for_all_of_them(self, balance) -> None:
        assert "Demurrage recovered" in trial_balance_page(balance, include_empty=True)

    def test_the_two_columns_are_unsigned(self, chart) -> None:
        """Cash here has only a credit against it, so its balance is negative.

        The trial balance still prints two positive columns, because that is
        what a trial balance is. The sign shows up on the balance sheet, which
        prints the balance rather than the columns.
        """
        balance = trial_balance(
            chart,
            [
                JournalEntry(
                    number="J-1",
                    posted_on=HARVEST,
                    memo="",
                    legs=(
                        debit("2100", Decimal("10.00")),
                        credit("1100", Decimal("10.00")),
                    ),
                )
            ],
            through=OCTOBER,
        )
        assert balance.balance_of("1100") == Decimal("-10.00")
        page = trial_balance_page(balance)
        assert "Cash in bank" in page
        assert "10.00-" not in page

    def test_a_negative_balance_prints_with_a_trailing_minus(self, chart) -> None:
        """Ledgers put the minus after the number, not in front of it."""
        balance = trial_balance(
            chart,
            [
                JournalEntry(
                    number="J-1",
                    posted_on=HARVEST,
                    memo="",
                    legs=(
                        debit("2100", Decimal("10.00")),
                        credit("1100", Decimal("10.00")),
                    ),
                )
            ],
            through=OCTOBER,
        )
        assert "10.00-" in balance_sheet(chart, balance)

    def test_an_unbalanced_set_says_so(self, chart) -> None:
        from bushelworks.domain.ledger import TrialBalance, TrialBalanceRow

        rows = (
            TrialBalanceRow(chart.get("1210"), Decimal("10.00"), Decimal("0.00")),
            TrialBalanceRow(chart.get("2100"), Decimal("0.00"), Decimal("7.00")),
        )
        page = trial_balance_page(TrialBalance(through=OCTOBER, rows=rows))
        assert "OUT OF BALANCE BY" in page


class TestIncomeStatement:
    def test_it_fits_eighty_columns(self, chart, balance) -> None:
        for line in income_statement(chart, balance, date(2024, 10, 1)).split("\n"):
            assert len(line) <= WIDTH, line

    def test_the_margin_is_revenue_less_expense(self, chart, balance) -> None:
        page = income_statement(chart, balance, date(2024, 10, 1))
        assert "margin" in page
        assert "161.05" in page  # 42.30 storage plus 118.75 drying

    def test_the_heading_carries_its_accounts(self, chart, balance) -> None:
        page = income_statement(chart, balance, date(2024, 10, 1))
        assert "Revenue" in page
        assert "Storage revenue" in page

    def test_an_expense_account_nothing_touched_is_left_out(self, chart, balance) -> None:
        page = income_statement(chart, balance, date(2024, 10, 1))
        assert "Dryer fuel" not in page

    def test_the_span_is_named(self, chart, balance) -> None:
        page = income_statement(chart, balance, date(2024, 10, 1))
        assert "2024-10-01 to 2024-10-31" in page


class TestBalanceSheet:
    def test_it_fits_eighty_columns(self, chart, balance) -> None:
        for line in balance_sheet(chart, balance).split("\n"):
            assert len(line) <= WIDTH, line

    def test_it_balances(self, chart, balance) -> None:
        page = balance_sheet(chart, balance)
        assert "in balance" in page
        assert "OUT OF BALANCE" not in page

    def test_the_period_margin_is_its_own_line(self, chart, balance) -> None:
        page = balance_sheet(chart, balance)
        assert "margin for the period" in page

    def test_the_day_is_named(self, chart, balance) -> None:
        assert "as at 2024-10-31" in balance_sheet(chart, balance)


class TestCsv:
    def test_it_has_a_header_and_a_row_per_account(self, balance) -> None:
        text = to_csv(balance)
        lines = text.strip().split("\n")
        assert lines[0] == "code,account,type,debits,credits,balance"
        assert len(lines) - 1 == len(balance.nonempty())

    def test_the_numbers_are_unformatted(self, balance) -> None:
        assert "4350.00" in to_csv(balance)
        assert "4,350.00" not in to_csv(balance)

    def test_a_name_with_a_comma_is_quoted(self, balance) -> None:
        assert '"Grain inventory, company owned"' in to_csv(balance)

    def test_the_empty_accounts_can_be_asked_for(self, balance) -> None:
        assert len(to_csv(balance, include_empty=True).strip().split("\n")) > len(
            to_csv(balance).strip().split("\n")
        )


@pytest.fixture
def house(tmp_path, rules, corn_ticket, monkeypatch):
    """A real database on disk so the CLI can open it by url."""
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
    monkeypatch.setenv("BUSHELWORKS_DATABASE_URL", url)
    return url


@pytest.fixture
def runner():
    return CliRunner()


class TestCommands:
    def test_the_chart_prints(self, runner, house) -> None:
        found = runner.invoke(cli, ["chart"], catch_exceptions=False)
        assert found.exit_code == 0
        assert "Grain inventory" in found.output

    def test_the_trial_balance_prints(self, runner, house) -> None:
        found = runner.invoke(
            cli, ["trial-balance", "2024-10-31"], catch_exceptions=False
        )
        assert found.exit_code == 0
        assert "TRIAL BALANCE" in found.output
        assert "OUT OF BALANCE" not in found.output

    def test_the_trial_balance_writes_a_csv(self, runner, house, tmp_path) -> None:
        out = tmp_path / "tb.csv"
        found = runner.invoke(
            cli,
            ["trial-balance", "2024-10-31", "--csv", str(out)],
            catch_exceptions=False,
        )
        assert found.exit_code == 0
        assert out.exists()
        assert out.read_text(encoding="utf-8").startswith("code,account,type")

    def test_the_income_statement_prints(self, runner, house) -> None:
        found = runner.invoke(
            cli, ["income", "2024-10-01", "2024-10-31"], catch_exceptions=False
        )
        assert found.exit_code == 0
        assert "INCOME STATEMENT" in found.output

    def test_the_balance_sheet_prints(self, runner, house) -> None:
        found = runner.invoke(
            cli, ["balance-sheet", "2024-10-31"], catch_exceptions=False
        )
        assert found.exit_code == 0
        assert "BALANCE SHEET" in found.output

    def test_the_journal_prints_its_legs(self, runner, house) -> None:
        found = runner.invoke(
            cli, ["journal", "2024-10-01", "2024-10-31"], catch_exceptions=False
        )
        assert found.exit_code == 0
        assert "dr" in found.output
        assert "cr" in found.output

    def test_reviewing_a_month_writes_nothing(self, runner, house) -> None:
        found = runner.invoke(cli, ["review", "2024-10"], catch_exceptions=False)
        assert found.exit_code == 0
        assert "would close clean" in found.output
        after = runner.invoke(cli, ["open-month"], catch_exceptions=False)
        assert "2024-10" in after.output

    def test_a_month_closes(self, runner, house) -> None:
        found = runner.invoke(
            cli,
            ["close-month", "2024-10", "--by", "office", "--on", "2024-11-06"],
            catch_exceptions=False,
        )
        assert found.exit_code == 0
        assert "2024-10 closed" in found.output
        after = runner.invoke(cli, ["open-month"], catch_exceptions=False)
        assert "2024-11" in after.output

    def test_a_month_that_will_not_close_says_why(self, runner, house) -> None:
        found = runner.invoke(cli, ["close-month", "2024-11", "--by", "office"])
        assert found.exit_code != 0
        assert "will not close" in found.output

    def test_force_closes_it_and_lists_what_was_outstanding(
        self, runner, house
    ) -> None:
        found = runner.invoke(
            cli,
            ["close-month", "2024-11", "--by", "office", "--force"],
            catch_exceptions=False,
        )
        assert found.exit_code == 0
        assert "outstanding items" in found.output

    def test_a_month_reopens_with_a_reason(self, runner, house) -> None:
        runner.invoke(
            cli,
            ["close-month", "2024-10", "--by", "office", "--on", "2024-11-06"],
            catch_exceptions=False,
        )
        found = runner.invoke(
            cli,
            ["reopen-month", "2024-10", "--reason", "a load was keyed late"],
            catch_exceptions=False,
        )
        assert found.exit_code == 0
        assert "reopened" in found.output

    def test_a_year_can_be_filed(self, runner, house) -> None:
        found = runner.invoke(cli, ["file-year", "2025"], catch_exceptions=False)
        assert found.exit_code == 0
        assert "12 months filed for 2025" in found.output

    def test_filing_a_year_twice_is_refused(self, runner, house) -> None:
        runner.invoke(cli, ["file-year", "2025"], catch_exceptions=False)
        found = runner.invoke(cli, ["file-year", "2025"])
        assert found.exit_code != 0
