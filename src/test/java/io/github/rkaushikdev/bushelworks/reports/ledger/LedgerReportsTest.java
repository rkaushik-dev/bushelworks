package io.github.rkaushikdev.bushelworks.reports.ledger;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.domain.ledger.Ledger.credit;
import static io.github.rkaushikdev.bushelworks.domain.ledger.Ledger.debit;
import static io.github.rkaushikdev.bushelworks.domain.ledger.Ledger.standardChart;
import static io.github.rkaushikdev.bushelworks.domain.ledger.Ledger.trialBalance;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.bushelworks.HouseFixtures;
import io.github.rkaushikdev.bushelworks.cli.Main;
import io.github.rkaushikdev.bushelworks.cli.Shell;
import io.github.rkaushikdev.bushelworks.domain.ledger.ChartOfAccounts;
import io.github.rkaushikdev.bushelworks.domain.ledger.JournalEntry;
import io.github.rkaushikdev.bushelworks.domain.ledger.TrialBalance;
import io.github.rkaushikdev.bushelworks.domain.ledger.TrialBalanceRow;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.services.dayend.DayEnd;
import io.github.rkaushikdev.bushelworks.services.monthend.MonthEnd;
import io.github.rkaushikdev.bushelworks.services.scalehouse.ScaleHouse;
import io.github.rkaushikdev.bushelworks.services.settlement.SettlementRun;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The three printed pages and the CSV, plus the commands that print them.
 *
 * <p>Widths are checked because these go to an eighty column printer and a line that runs over wraps
 * into the next one, which the office reads as a missing account. The repository has already shipped
 * that once on the statement.
 */
class LedgerReportsTest {

    static final LocalDate HARVEST = LocalDate.of(2024, 10, 3);
    static final LocalDate OCTOBER = LocalDate.of(2024, 10, 31);

    ChartOfAccounts chart;
    TrialBalance balance;

    /** One settlement and the cheque that paid it, posted. */
    @BeforeEach
    void october() {
        chart = standardChart();
        List<JournalEntry> journal = List.of(
                new JournalEntry(
                        "J-202410-0001",
                        HARVEST,
                        "settlement S-2024-0001",
                        List.of(
                                debit("1210", d("4350.00")),
                                credit("4100", d("42.30")),
                                credit("4200", d("118.75")),
                                credit("2100", d("4188.95"))),
                        "settlement",
                        "S-2024-0001"),
                new JournalEntry(
                        "J-202410-0002",
                        LocalDate.of(2024, 10, 20),
                        "cheque run",
                        List.of(debit("2100", d("4188.95")), credit("1100", d("4188.95"))),
                        "payment",
                        "C-2024-0100"));
        balance = trialBalance(chart, journal, OCTOBER, LocalDate.of(2024, 10, 1));
    }

    /** Cash with only a credit against it, so its balance is negative. */
    TrialBalance cashOverdrawn() {
        return trialBalance(
                chart,
                List.of(new JournalEntry(
                        "J-1", HARVEST, "", List.of(debit("2100", d("10.00")), credit("1100", d("10.00"))))),
                OCTOBER);
    }

    static void fitsTheWidth(String page) {
        assertThat(page.split("\n", -1))
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line.length()).as(line).isLessThanOrEqualTo(LedgerPages.WIDTH));
    }

    @Nested
    class TrialBalancePage {

        @Test
        void itFitsEightyColumns() {
            fitsTheWidth(LedgerPages.trialBalancePage(balance));
        }

        @Test
        void theTotalsAgree() {
            String page = LedgerPages.trialBalancePage(balance);
            assertThat(page).contains("4,350.00").doesNotContain("OUT OF BALANCE");
        }

        @Test
        void theAccountsThatWereTouchedAreOnIt() {
            assertThat(LedgerPages.trialBalancePage(balance))
                    .contains("Grain inventory, company owned", "Storage revenue");
        }

        @Test
        void theUntouchedOnesAreNot() {
            assertThat(LedgerPages.trialBalancePage(balance)).doesNotContain("Demurrage recovered");
        }

        @Test
        void theAuditorCanAskForAllOfThem() {
            assertThat(LedgerPages.trialBalancePage(balance, true)).contains("Demurrage recovered");
        }

        /**
         * The trial balance still prints two positive columns, because that is what a trial balance
         * is. The sign shows up on the balance sheet, which prints the balance rather than the columns.
         */
        @Test
        void theTwoColumnsAreUnsigned() {
            TrialBalance overdrawn = cashOverdrawn();
            assertThat(overdrawn.balanceOf("1100")).isEqualTo(d("-10.00"));
            String page = LedgerPages.trialBalancePage(overdrawn);
            assertThat(page).contains("Cash in bank").doesNotContain("10.00-");
        }

        /** Ledgers put the minus after the number, not in front of it. */
        @Test
        void aNegativeBalancePrintsWithATrailingMinus() {
            assertThat(LedgerPages.balanceSheet(chart, cashOverdrawn())).contains("10.00-");
        }

        @Test
        void anUnbalancedSetSaysSo() {
            List<TrialBalanceRow> rows = List.of(
                    new TrialBalanceRow(chart.get("1210"), d("10.00"), d("0.00")),
                    new TrialBalanceRow(chart.get("2100"), d("0.00"), d("7.00")));
            assertThat(LedgerPages.trialBalancePage(new TrialBalance(OCTOBER, rows)))
                    .contains("OUT OF BALANCE BY 3.00");
        }
    }

    @Nested
    class IncomeStatement {

        @Test
        void itFitsEightyColumns() {
            fitsTheWidth(LedgerPages.incomeStatement(chart, balance, LocalDate.of(2024, 10, 1)));
        }

        @Test
        void theMarginIsRevenueLessExpense() {
            String page = LedgerPages.incomeStatement(chart, balance, LocalDate.of(2024, 10, 1));
            assertThat(page).contains("margin");
            // 42.30 storage plus 118.75 drying.
            assertThat(page).contains("161.05");
        }

        @Test
        void theHeadingCarriesItsAccounts() {
            assertThat(LedgerPages.incomeStatement(chart, balance, LocalDate.of(2024, 10, 1)))
                    .contains("Revenue", "Storage revenue");
        }

        @Test
        void anExpenseAccountNothingTouchedIsLeftOut() {
            assertThat(LedgerPages.incomeStatement(chart, balance, LocalDate.of(2024, 10, 1)))
                    .doesNotContain("Dryer fuel");
        }

        @Test
        void theSpanIsNamed() {
            assertThat(LedgerPages.incomeStatement(chart, balance, LocalDate.of(2024, 10, 1)))
                    .contains("2024-10-01 to 2024-10-31");
        }
    }

    @Nested
    class BalanceSheet {

        @Test
        void itFitsEightyColumns() {
            fitsTheWidth(LedgerPages.balanceSheet(chart, balance));
        }

        @Test
        void itBalances() {
            assertThat(LedgerPages.balanceSheet(chart, balance))
                    .contains("in balance")
                    .doesNotContain("OUT OF BALANCE");
        }

        @Test
        void thePeriodMarginIsItsOwnLine() {
            assertThat(LedgerPages.balanceSheet(chart, balance)).contains("margin for the period");
        }

        @Test
        void theDayIsNamed() {
            assertThat(LedgerPages.balanceSheet(chart, balance)).contains("as at 2024-10-31");
        }
    }

    @Nested
    class Csv {

        static List<String> lines(String text) {
            return List.of(text.strip().split("\n", -1));
        }

        @Test
        void itHasAHeaderAndARowPerAccount() {
            List<String> lines = lines(LedgerPages.toCsv(balance));
            assertThat(lines.get(0)).isEqualTo("code,account,type,debits,credits,balance");
            assertThat(lines).hasSize(balance.nonempty().size() + 1);
        }

        @Test
        void theNumbersAreUnformatted() {
            assertThat(LedgerPages.toCsv(balance)).contains("4350.00").doesNotContain("4,350.00");
        }

        @Test
        void aNameWithACommaIsQuoted() {
            assertThat(LedgerPages.toCsv(balance)).contains("\"Grain inventory, company owned\"");
        }

        @Test
        void theEmptyAccountsCanBeAskedFor() {
            assertThat(lines(LedgerPages.toCsv(balance, true)).size())
                    .isGreaterThan(lines(LedgerPages.toCsv(balance)).size());
        }
    }

    /** A real house on file, so the command line can open it by URL. */
    @Nested
    class Commands {

        @TempDir
        Path tmp;

        Database database;
        Shell shell;

        @BeforeEach
        void house() {
            database = TestDatabase.fresh();
            shell = Shell.on(database);
            RuleBook rules = HouseFixtures.rules();
            ScaleTicket corn = HouseFixtures.cornTicket();
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                uow.ledger().fileChart(standardChart());
                MonthEnd.fileYear(uow, 2024);
                for (int month = 1; month < 10; month++) {
                    MonthEnd.closePeriod(
                            uow, String.format(Locale.ROOT, "2024-%02d", month), "office", LocalDate.of(2024, 10, 1));
                }
                for (String number : List.of("A-10441", "A-10442")) {
                    ScaleHouse.recordLoad(
                            uow, corn.toBuilder().ticketNumber(number).build(), "MERRICK", rules);
                }
                uow.commit();
            }
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                SettlementRun.settleProducer(uow, "MERRICK", "HOME", rules, HARVEST, d("4.35"));
                uow.commit();
            }
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                DayEnd.runClose(uow, HARVEST);
                uow.commit();
            }
        }

        @Test
        void theChartPrints() {
            Shell.Result found = shell.run("chart");
            assertThat(found.code()).isZero();
            assertThat(found.output()).contains("Grain inventory");
        }

        @Test
        void theTrialBalancePrints() {
            Shell.Result found = shell.run("trial-balance", "2024-10-31");
            assertThat(found.code()).isZero();
            assertThat(found.output()).contains("TRIAL BALANCE").doesNotContain("OUT OF BALANCE");
        }

        @Test
        void theTrialBalanceWritesACsv() throws IOException {
            Path out = tmp.resolve("tb.csv");
            Shell.Result found = shell.run("trial-balance", "2024-10-31", "--csv", out.toString());
            assertThat(found.code()).isZero();
            assertThat(out).exists();
            assertThat(Files.readString(out, StandardCharsets.UTF_8)).startsWith("code,account,type");
        }

        @Test
        void theIncomeStatementPrints() {
            Shell.Result found = shell.run("income", "2024-10-01", "2024-10-31");
            assertThat(found.code()).isZero();
            assertThat(found.output()).contains("INCOME STATEMENT");
        }

        @Test
        void theBalanceSheetPrints() {
            Shell.Result found = shell.run("balance-sheet", "2024-10-31");
            assertThat(found.code()).isZero();
            assertThat(found.output()).contains("BALANCE SHEET");
        }

        @Test
        void theJournalPrintsItsLegs() {
            Shell.Result found = shell.run("journal", "2024-10-01", "2024-10-31");
            assertThat(found.code()).isZero();
            assertThat(found.output()).contains("dr", "cr");
        }

        @Test
        void reviewingAMonthWritesNothing() {
            Shell.Result found = shell.run("review", "2024-10");
            assertThat(found.code()).isZero();
            assertThat(found.output()).contains("would close clean");
            assertThat(shell.run("open-month").output()).contains("2024-10");
        }

        @Test
        void aMonthCloses() {
            Shell.Result found = shell.run("close-month", "2024-10", "--by", "office", "--on", "2024-11-06");
            assertThat(found.code()).isZero();
            assertThat(found.output()).contains("2024-10 closed");
            assertThat(shell.run("open-month").output()).contains("2024-11");
        }

        @Test
        void aMonthThatWillNotCloseSaysWhy() {
            Shell.Result found = shell.run("close-month", "2024-11", "--by", "office");
            assertThat(found.code()).isEqualTo(Main.REFUSED);
            assertThat(found.output()).contains("will not close");
        }

        @Test
        void forceClosesItAndListsWhatWasOutstanding() {
            Shell.Result found = shell.run("close-month", "2024-11", "--by", "office", "--force");
            assertThat(found.code()).isZero();
            assertThat(found.output()).contains("outstanding items");
        }

        @Test
        void aMonthReopensWithAReason() {
            shell.run("close-month", "2024-10", "--by", "office", "--on", "2024-11-06");
            Shell.Result found = shell.run("reopen-month", "2024-10", "--reason", "a load was keyed late");
            assertThat(found.code()).isZero();
            assertThat(found.output()).contains("reopened");
        }

        @Test
        void aYearCanBeFiled() {
            Shell.Result found = shell.run("file-year", "2025");
            assertThat(found.code()).isZero();
            assertThat(found.output()).contains("12 months filed for 2025");
        }

        @Test
        void filingAYearTwiceIsRefused() {
            shell.run("file-year", "2025");
            Shell.Result again = shell.run("file-year", "2025");
            assertThat(again.code()).isEqualTo(Main.REFUSED);
            assertThat(again.err()).contains("conflict: period 2025-01 is already on file");
        }
    }
}
