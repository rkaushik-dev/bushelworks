"""The chart, the entry that has to balance, and the trial balance off both.

The numbers here are small enough to add in your head on purpose. A ledger test
whose expected value came out of the code it is testing proves the code has not
changed, which is not the same as proving it is right.
"""

from datetime import date
from decimal import Decimal

import pytest

from bushelworks.domain.ledger import (
    Account,
    AccountNotFound,
    AccountType,
    ChartOfAccounts,
    JournalEntry,
    LedgerError,
    Leg,
    Side,
    credit,
    debit,
    rollup,
    standard_chart,
    trial_balance,
)
from bushelworks.errors import ConflictError

MAY = date(2025, 5, 12)


@pytest.fixture
def chart() -> ChartOfAccounts:
    return standard_chart()


def entry(number: str, *legs: Leg, on: date = MAY) -> JournalEntry:
    return JournalEntry(number=number, posted_on=on, memo="test", legs=legs)


class TestSides:
    def test_assets_and_expenses_increase_on_the_debit_side(self) -> None:
        assert AccountType.ASSET.normal_side is Side.DEBIT
        assert AccountType.EXPENSE.normal_side is Side.DEBIT

    def test_everything_else_increases_on_the_credit_side(self) -> None:
        assert AccountType.LIABILITY.normal_side is Side.CREDIT
        assert AccountType.EQUITY.normal_side is Side.CREDIT
        assert AccountType.REVENUE.normal_side is Side.CREDIT

    def test_a_side_knows_its_opposite(self) -> None:
        assert Side.DEBIT.opposite is Side.CREDIT
        assert Side.CREDIT.opposite is Side.DEBIT

    def test_revenue_and_expense_are_not_on_the_balance_sheet(self) -> None:
        assert AccountType.ASSET.on_balance_sheet
        assert AccountType.LIABILITY.on_balance_sheet
        assert AccountType.EQUITY.on_balance_sheet
        assert not AccountType.REVENUE.on_balance_sheet
        assert not AccountType.EXPENSE.on_balance_sheet


class TestChart:
    def test_the_filed_chart_has_the_accounts_a_settlement_needs(
        self, chart: ChartOfAccounts
    ) -> None:
        for code in ("1210", "2100", "2200", "4100", "4200", "1300"):
            assert code in chart

    def test_a_parent_has_to_be_on_file_before_its_child(self) -> None:
        with pytest.raises(AccountNotFound):
            ChartOfAccounts([Account("1100", "Cash", AccountType.ASSET, parent="1000")])

    def test_a_child_cannot_change_type_from_its_parent(self) -> None:
        with pytest.raises(LedgerError, match="which is a asset"):
            ChartOfAccounts(
                [
                    Account("1000", "Assets", AccountType.ASSET),
                    Account("1100", "Storage", AccountType.REVENUE, parent="1000"),
                ]
            )

    def test_the_same_code_cannot_be_filed_twice(self, chart: ChartOfAccounts) -> None:
        with pytest.raises(ConflictError, match="already in the chart"):
            chart.add(Account("1100", "Cash again", AccountType.ASSET))

    def test_an_account_cannot_parent_itself(self) -> None:
        with pytest.raises(LedgerError, match="its own parent"):
            Account("1000", "Assets", AccountType.ASSET, parent="1000")

    def test_an_account_needs_a_name(self) -> None:
        with pytest.raises(LedgerError, match="needs a name"):
            Account("1000", "   ", AccountType.ASSET)

    def test_a_code_with_space_around_it_is_refused(self) -> None:
        with pytest.raises(LedgerError, match="bare code"):
            Account(" 1000 ", "Assets", AccountType.ASSET)

    def test_an_account_with_children_is_a_heading(self, chart: ChartOfAccounts) -> None:
        assert chart.is_heading("1200")
        assert not chart.is_heading("1210")

    def test_a_heading_will_not_take_a_posting_and_says_where_to_put_it(
        self, chart: ChartOfAccounts
    ) -> None:
        with pytest.raises(LedgerError, match="post to one of 1210, 1220"):
            chart.postable("1200")

    def test_a_leaf_is_postable(self, chart: ChartOfAccounts) -> None:
        assert chart.postable("1210").name == "Grain inventory, company owned"

    def test_an_unknown_code_is_a_lookup_failure(self, chart: ChartOfAccounts) -> None:
        with pytest.raises(AccountNotFound, match="no account 9999"):
            chart.get("9999")

    def test_children_come_back_in_code_order(self, chart: ChartOfAccounts) -> None:
        assert [a.code for a in chart.children_of("1200")] == ["1210", "1220"]

    def test_lineage_runs_from_the_top_down(self, chart: ChartOfAccounts) -> None:
        assert [a.code for a in chart.lineage("1210")] == ["1000", "1200", "1210"]

    def test_of_type_picks_one_kind_out(self, chart: ChartOfAccounts) -> None:
        revenue = chart.of_type(AccountType.REVENUE)
        assert [a.code for a in revenue] == ["4000", "4100", "4200", "4300", "4400", "4500"]

    def test_iterating_the_chart_gives_code_order(self, chart: ChartOfAccounts) -> None:
        codes = [a.code for a in chart]
        assert codes == sorted(codes)
        assert len(codes) == len(chart)


class TestLeg:
    def test_a_leg_carries_a_positive_amount(self) -> None:
        with pytest.raises(LedgerError, match="positive amount"):
            debit("1100", Decimal("-5.00"))

    def test_a_zero_leg_is_refused(self) -> None:
        with pytest.raises(LedgerError, match="positive amount"):
            credit("1100", Decimal("0.00"))

    def test_a_leg_rounds_to_cents_on_the_way_in(self) -> None:
        assert debit("1100", Decimal("10.005")).dollars == Decimal("10.01")

    def test_a_debit_increases_an_asset(self, chart: ChartOfAccounts) -> None:
        cash = chart.get("1100")
        assert debit("1100", Decimal("100.00")).effect_on(cash) == Decimal("100.00")

    def test_a_credit_reduces_an_asset(self, chart: ChartOfAccounts) -> None:
        cash = chart.get("1100")
        assert credit("1100", Decimal("40.00")).effect_on(cash) == Decimal("-40.00")

    def test_a_credit_increases_revenue(self, chart: ChartOfAccounts) -> None:
        storage = chart.get("4100")
        assert credit("4100", Decimal("12.50")).effect_on(storage) == Decimal("12.50")

    def test_a_debit_reduces_revenue(self, chart: ChartOfAccounts) -> None:
        storage = chart.get("4100")
        assert debit("4100", Decimal("12.50")).effect_on(storage) == Decimal("-12.50")


class TestJournalEntry:
    def test_an_entry_needs_two_legs(self) -> None:
        with pytest.raises(LedgerError, match="at least two legs"):
            entry("J-1", debit("1210", Decimal("100.00")))

    def test_a_balanced_entry_balances(self) -> None:
        posting = entry(
            "J-2",
            debit("1210", Decimal("4350.00")),
            credit("2100", Decimal("4350.00")),
        )
        assert posting.balances
        assert posting.debits == Decimal("4350.00")
        assert posting.credits == Decimal("4350.00")
        posting.check_balances()

    def test_an_unbalanced_entry_says_what_it_is_out_by(self) -> None:
        posting = entry(
            "J-3",
            debit("1210", Decimal("4350.00")),
            credit("2100", Decimal("4300.00")),
        )
        assert not posting.balances
        with pytest.raises(LedgerError, match="out by 50.00"):
            posting.check_balances()

    def test_a_many_legged_entry_balances_on_the_totals(self) -> None:
        posting = entry(
            "J-4",
            debit("1210", Decimal("4350.00")),
            credit("4100", Decimal("42.30")),
            credit("4200", Decimal("118.75")),
            credit("2200", Decimal("4.35")),
            credit("2100", Decimal("4184.60")),
        )
        posting.check_balances()

    def test_checking_against_the_chart_refuses_a_heading(
        self, chart: ChartOfAccounts
    ) -> None:
        posting = entry(
            "J-5",
            debit("1200", Decimal("100.00")),
            credit("2100", Decimal("100.00")),
        )
        with pytest.raises(LedgerError, match="post to one of"):
            posting.check_against(chart)

    def test_checking_against_the_chart_refuses_an_unknown_account(
        self, chart: ChartOfAccounts
    ) -> None:
        posting = entry(
            "J-6",
            debit("9999", Decimal("100.00")),
            credit("2100", Decimal("100.00")),
        )
        with pytest.raises(AccountNotFound):
            posting.check_against(chart)

    def test_a_good_entry_passes_the_chart_check(self, chart: ChartOfAccounts) -> None:
        posting = entry(
            "J-7",
            debit("1210", Decimal("100.00")),
            credit("2100", Decimal("100.00")),
        )
        posting.check_against(chart)

    def test_touching_finds_the_legs_on_one_account(self) -> None:
        posting = entry(
            "J-8",
            debit("1210", Decimal("100.00")),
            credit("2100", Decimal("60.00")),
            credit("2100", Decimal("40.00")),
        )
        assert len(posting.touching("2100")) == 2
        assert posting.touching("4100") == ()


class TestReversal:
    def test_a_reversal_flips_every_side(self) -> None:
        original = entry(
            "J-9",
            debit("1210", Decimal("4350.00")),
            credit("2100", Decimal("4350.00")),
        )
        back = original.reverse("J-10", date(2025, 5, 16))
        assert [leg.side for leg in back.legs] == [Side.CREDIT, Side.DEBIT]
        assert back.debits == original.credits
        back.check_balances()

    def test_a_reversal_keeps_the_source_it_came_from(self) -> None:
        original = JournalEntry(
            number="J-11",
            posted_on=MAY,
            memo="settlement S-2025-0004",
            legs=(debit("1210", Decimal("10.00")), credit("2100", Decimal("10.00"))),
            source="settlement",
            source_reference="S-2025-0004",
        )
        back = original.reverse("J-12", date(2025, 5, 16))
        assert back.source == "settlement"
        assert back.source_reference == "S-2025-0004"
        assert back.memo == "reversal of J-11"

    def test_the_pair_nets_to_nothing(self, chart: ChartOfAccounts) -> None:
        original = entry(
            "J-13",
            debit("1210", Decimal("4350.00")),
            credit("2100", Decimal("4350.00")),
        )
        back = original.reverse("J-14", date(2025, 5, 16))
        balance = trial_balance(chart, [original, back], through=date(2025, 5, 31))
        assert balance.balance_of("1210") == Decimal("0.00")
        assert balance.balance_of("2100") == Decimal("0.00")


class TestTrialBalance:
    @pytest.fixture
    def journal(self) -> list[JournalEntry]:
        return [
            entry(
                "J-100",
                debit("1210", Decimal("4350.00")),
                credit("4100", Decimal("42.30")),
                credit("2200", Decimal("4.35")),
                credit("2100", Decimal("4303.35")),
                on=date(2025, 5, 5),
            ),
            entry(
                "J-101",
                debit("2100", Decimal("4303.35")),
                credit("1100", Decimal("4303.35")),
                on=date(2025, 5, 20),
            ),
        ]

    def test_it_balances(
        self, chart: ChartOfAccounts, journal: list[JournalEntry]
    ) -> None:
        balance = trial_balance(chart, journal, through=date(2025, 5, 31))
        assert balance.balances
        assert balance.debits == balance.credits

    def test_the_payable_is_cleared_by_the_cheque(
        self, chart: ChartOfAccounts, journal: list[JournalEntry]
    ) -> None:
        balance = trial_balance(chart, journal, through=date(2025, 5, 31))
        assert balance.balance_of("2100") == Decimal("0.00")
        assert balance.balance_of("1100") == Decimal("-4303.35")

    def test_revenue_reads_positive_on_its_own_side(
        self, chart: ChartOfAccounts, journal: list[JournalEntry]
    ) -> None:
        balance = trial_balance(chart, journal, through=date(2025, 5, 31))
        assert balance.balance_of("4100") == Decimal("42.30")

    def test_an_entry_after_the_cutoff_is_left_out(
        self, chart: ChartOfAccounts, journal: list[JournalEntry]
    ) -> None:
        balance = trial_balance(chart, journal, through=date(2025, 5, 10))
        assert balance.balance_of("2100") == Decimal("4303.35")
        assert balance.balance_of("1100") == Decimal("0.00")

    def test_a_since_date_makes_it_a_period_rather_than_a_running_total(
        self, chart: ChartOfAccounts, journal: list[JournalEntry]
    ) -> None:
        balance = trial_balance(
            chart, journal, through=date(2025, 5, 31), since=date(2025, 5, 10)
        )
        assert balance.balance_of("1210") == Decimal("0.00")
        assert balance.balance_of("1100") == Decimal("-4303.35")

    def test_headings_are_not_rows_on_it(
        self, chart: ChartOfAccounts, journal: list[JournalEntry]
    ) -> None:
        balance = trial_balance(chart, journal, through=date(2025, 5, 31))
        codes = [row.account.code for row in balance.rows]
        assert "1200" not in codes
        assert "1210" in codes

    def test_nonempty_drops_the_accounts_nothing_landed_in(
        self, chart: ChartOfAccounts, journal: list[JournalEntry]
    ) -> None:
        balance = trial_balance(chart, journal, through=date(2025, 5, 31))
        touched = {row.account.code for row in balance.nonempty()}
        assert touched == {"1210", "4100", "2200", "2100", "1100"}

    def test_a_subtotal_adds_one_kind_of_account(
        self, chart: ChartOfAccounts, journal: list[JournalEntry]
    ) -> None:
        balance = trial_balance(chart, journal, through=date(2025, 5, 31))
        assert balance.subtotal(AccountType.REVENUE) == Decimal("42.30")
        assert balance.subtotal(AccountType.LIABILITY) == Decimal("4.35")

    def test_asking_for_an_account_that_is_not_on_it_fails(
        self, chart: ChartOfAccounts, journal: list[JournalEntry]
    ) -> None:
        balance = trial_balance(chart, journal, through=date(2025, 5, 31))
        with pytest.raises(AccountNotFound, match="not on this trial balance"):
            balance.row("1200")


class TestRollup:
    def test_a_heading_carries_what_its_children_hold(
        self, chart: ChartOfAccounts
    ) -> None:
        journal = [
            entry(
                "J-200",
                debit("1210", Decimal("100.00")),
                debit("1220", Decimal("250.00")),
                credit("2100", Decimal("350.00")),
            )
        ]
        balance = trial_balance(chart, journal, through=date(2025, 5, 31))
        totals = rollup(chart, balance)
        assert totals["1210"] == Decimal("100.00")
        assert totals["1220"] == Decimal("250.00")
        assert totals["1200"] == Decimal("350.00")
        assert totals["1000"] == Decimal("350.00")

    def test_every_account_in_the_chart_appears(self, chart: ChartOfAccounts) -> None:
        balance = trial_balance(chart, [], through=date(2025, 5, 31))
        totals = rollup(chart, balance)
        assert set(totals) == {a.code for a in chart}
        assert all(v == Decimal("0.00") for v in totals.values())
