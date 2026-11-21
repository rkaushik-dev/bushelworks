package io.github.rkaushikdev.bushelworks.services.closing;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.domain.ledger.Ledger.credit;
import static io.github.rkaushikdev.bushelworks.domain.ledger.Ledger.debit;
import static io.github.rkaushikdev.bushelworks.domain.ledger.Ledger.standardChart;
import static io.github.rkaushikdev.bushelworks.domain.ledger.Ledger.trialBalance;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.Capture;
import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.HouseFixtures;
import io.github.rkaushikdev.bushelworks.cli.Main;
import io.github.rkaushikdev.bushelworks.cli.Shell;
import io.github.rkaushikdev.bushelworks.domain.ledger.Account;
import io.github.rkaushikdev.bushelworks.domain.ledger.AccountType;
import io.github.rkaushikdev.bushelworks.domain.ledger.ChartOfAccounts;
import io.github.rkaushikdev.bushelworks.domain.ledger.JournalEntry;
import io.github.rkaushikdev.bushelworks.domain.ledger.LedgerError;
import io.github.rkaushikdev.bushelworks.domain.ledger.Leg;
import io.github.rkaushikdev.bushelworks.domain.ledger.Side;
import io.github.rkaushikdev.bushelworks.domain.ledger.TrialBalance;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.services.dayend.DayEnd;
import io.github.rkaushikdev.bushelworks.services.monthend.MonthEnd;
import io.github.rkaushikdev.bushelworks.services.scalehouse.ScaleHouse;
import io.github.rkaushikdev.bushelworks.services.settlement.SettlementRun;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Closing a year into equity.
 *
 * <p>The check that matters is the last one in {@link TheEntry}: run the trial balance again with the
 * closing entry in it and every revenue and expense account has to read zero. Everything else here
 * is about which equity account the money lands in, which is the part houses disagree about.
 */
class ClosingTest {

    static final LocalDate HARVEST = LocalDate.of(2024, 10, 3);
    static final LocalDate NEW_YEAR = LocalDate.of(2024, 12, 31);
    static final BigDecimal SPOT = new BigDecimal("4.35");

    ChartOfAccounts chart;
    TrialBalance balance;

    @BeforeEach
    void standardBooks() {
        chart = standardChart();
        balance = aYear(chart);
    }

    /** The settlement and the gas bill: storage and drying earned, dryer fuel spent. */
    static List<JournalEntry> journal() {
        return List.of(
                new JournalEntry(
                        "J-1",
                        HARVEST,
                        "settlement",
                        List.of(
                                debit("1210", d("4350.00")),
                                credit("4100", d("400.00")),
                                credit("4200", d("600.00")),
                                credit("2100", d("3350.00")))),
                new JournalEntry(
                        "J-2",
                        LocalDate.of(2024, 11, 4),
                        "gas bill",
                        List.of(debit("5200", d("250.00")), credit("1100", d("250.00")))));
    }

    /** Storage and drying earned, dryer fuel spent, on one trial balance. */
    static TrialBalance aYear(ChartOfAccounts chart) {
        return trialBalance(chart, journal(), NEW_YEAR, LocalDate.of(2024, 1, 1));
    }

    static Leg only(JournalEntry entry, String accountCode) {
        return entry.touching(accountCode).get(0);
    }

    @Nested
    class TheEntry {

        @Test
        void itBalances() {
            assertThatCode(() -> Closing.closingEntry(chart, balance, "Y-1", NEW_YEAR)
                            .checkBalances())
                    .doesNotThrowAnyException();
        }

        @Test
        void everyRevenueAccountIsDebitedForWhatItHolds() {
            Leg storage = only(Closing.closingEntry(chart, balance, "Y-1", NEW_YEAR), "4100");
            assertThat(storage.side()).isEqualTo(Side.DEBIT);
            assertThat(storage.dollars()).isEqualTo(d("400.00"));
        }

        @Test
        void everyExpenseAccountIsCredited() {
            Leg fuel = only(Closing.closingEntry(chart, balance, "Y-1", NEW_YEAR), "5200");
            assertThat(fuel.side()).isEqualTo(Side.CREDIT);
            assertThat(fuel.dollars()).isEqualTo(d("250.00"));
        }

        @Test
        void theMarginReachesRetainedEarnings() {
            Leg retained = only(Closing.closingEntry(chart, balance, "Y-1", NEW_YEAR), Closing.RETAINED_EARNINGS);
            assertThat(retained.side()).isEqualTo(Side.CREDIT);
            assertThat(retained.dollars()).isEqualTo(d("750.00")); // 1000 earned less 250 spent
        }

        @Test
        void anAccountWithNothingInItGetsNoLeg() {
            assertThat(Closing.closingEntry(chart, balance, "Y-1", NEW_YEAR).touching("4500"))
                    .isEmpty();
        }

        @Test
        void aBalanceSheetAccountIsLeftAlone() {
            JournalEntry entry = Closing.closingEntry(chart, balance, "Y-1", NEW_YEAR);
            assertThat(entry.touching("1210")).isEmpty();
            assertThat(entry.touching("2100")).isEmpty();
        }

        @Test
        void itPointsAtTheYearItClosed() {
            JournalEntry entry = Closing.closingEntry(chart, balance, "Y-1", NEW_YEAR);
            assertThat(entry.source()).isEqualTo(Closing.SOURCE);
            assertThat(entry.sourceReference()).isEqualTo("2024");
        }

        @Test
        void aLossDebitsRetainedEarnings() {
            List<JournalEntry> badYear = List.of(new JournalEntry(
                    "J-1",
                    HARVEST,
                    "a bad year",
                    List.of(debit("5200", d("900.00")), credit("4100", d("100.00")), credit("1100", d("800.00")))));
            JournalEntry entry = Closing.closingEntry(chart, trialBalance(chart, badYear, NEW_YEAR), "Y-1", NEW_YEAR);
            Leg retained = only(entry, Closing.RETAINED_EARNINGS);
            assertThat(retained.side()).isEqualTo(Side.DEBIT);
            assertThat(retained.dollars()).isEqualTo(d("800.00"));
            assertThat(entry.balances()).isTrue();
        }

        /** More reversed than sold, so storage revenue sits on the debit side. */
        @Test
        void aContraRevenueBalanceClosesTheOtherWay() {
            List<JournalEntry> reversals = List.of(new JournalEntry(
                    "J-1", HARVEST, "reversals", List.of(debit("4100", d("60.00")), credit("1100", d("60.00")))));
            TrialBalance contra = trialBalance(chart, reversals, NEW_YEAR);
            assertThat(contra.balanceOf("4100")).isEqualTo(d("-60.00"));
            JournalEntry entry = Closing.closingEntry(chart, contra, "Y-1", NEW_YEAR);
            Leg storage = only(entry, "4100");
            assertThat(storage.side()).isEqualTo(Side.CREDIT);
            assertThat(storage.dollars()).isEqualTo(d("60.00"));
            assertThat(entry.balances()).isTrue();
        }

        @Test
        void aYearWithNothingInItWillNotClose() {
            TrialBalance empty = trialBalance(chart, List.of(), NEW_YEAR);
            assertThatThrownBy(() -> Closing.closingEntry(chart, empty, "Y-1", NEW_YEAR))
                    .isInstanceOf(ClosingError.class)
                    .hasMessageContaining("nothing to close");
        }

        @Test
        void aRetainedAccountThatIsAHeadingIsRefused() {
            assertThatThrownBy(() -> Closing.closingEntry(chart, balance, "Y-1", NEW_YEAR, "3000"))
                    .isInstanceOf(LedgerError.class)
                    .hasMessageContaining("post to one of");
        }

        /** The whole point, checked by running the books again with it in. */
        @Test
        void theAccountsReadZeroAfterwards() {
            List<JournalEntry> withTheClose = new ArrayList<>(journal());
            withTheClose.add(Closing.closingEntry(chart, balance, "Y-1", NEW_YEAR));
            TrialBalance after = trialBalance(chart, withTheClose, NEW_YEAR);
            assertThat(after.balances()).isTrue();
            assertThat(Closing.accountsLeftOpen(chart, after)).isEmpty();
            assertThat(after.balanceOf(Closing.RETAINED_EARNINGS)).isEqualTo(d("750.00"));
            assertThat(after.subtotal(AccountType.REVENUE)).isEqualTo(d("0.00"));
            assertThat(after.subtotal(AccountType.EXPENSE)).isEqualTo(d("0.00"));
        }
    }

    @Nested
    class ClosesTo {

        /** A chart where storage margin goes to a patronage account. */
        ChartOfAccounts coOp() {
            ChartOfAccounts coOp = standardChart();
            coOp.add(Account.of("3200", "Patronage to allocate", AccountType.EQUITY)
                    .withParent("3000"));
            return coOp;
        }

        /** The co-op chart with some accounts told where to close. */
        ChartOfAccounts directed(Map<String, String> destinations) {
            List<Account> accounts = new ArrayList<>();
            for (Account account : coOp()) {
                String target = destinations.get(account.code());
                accounts.add(target == null ? account : account.withClosesTo(target));
            }
            return new ChartOfAccounts(accounts);
        }

        JournalEntry closed(ChartOfAccounts directed) {
            return Closing.closingEntry(directed, aYear(directed), "Y-1", NEW_YEAR);
        }

        @Test
        void anAccountCanNameWhereItCloses() {
            Leg patronage = only(closed(directed(Map.of("4100", "3200"))), "3200");
            assertThat(patronage.side()).isEqualTo(Side.CREDIT);
            assertThat(patronage.dollars()).isEqualTo(d("400.00"));
        }

        @Test
        void whatIsLeftStillGoesToRetainedEarnings() {
            JournalEntry entry = closed(directed(Map.of("4100", "3200")));
            assertThat(only(entry, Closing.RETAINED_EARNINGS).dollars()).isEqualTo(d("350.00")); // 600 less 250
            assertThat(entry.balances()).isTrue();
        }

        /** Set it on 4000 and every revenue account under it follows. */
        @Test
        void aHeadingPassesItsDestinationDown() {
            JournalEntry entry = closed(directed(Map.of("4000", "3200")));
            assertThat(only(entry, "3200").dollars()).isEqualTo(d("1000.00")); // both revenue accounts
            Leg retained = only(entry, Closing.RETAINED_EARNINGS);
            assertThat(retained.side()).isEqualTo(Side.DEBIT);
            assertThat(retained.dollars()).isEqualTo(d("250.00")); // only the fuel
        }

        @Test
        void theNearestSettingWins() {
            JournalEntry entry = closed(directed(Map.of("4000", "3200", "4100", Closing.RETAINED_EARNINGS)));
            assertThat(only(entry, "3200").dollars()).isEqualTo(d("600.00"));
        }
    }

    /** The books of a whole house, kept on file. */
    abstract class OnFile {

        Database database;
        RuleBook rules;
        ScaleTicket cornTicket;

        @BeforeEach
        void freshDatabase() {
            database = TestDatabase.fresh();
            rules = HouseFixtures.rules();
            cornTicket = HouseFixtures.cornTicket();
        }

        /** A finished 2024: a chart, twelve closed months and one posted settlement. */
        void house() {
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                uow.ledger().fileChart(standardChart());
                MonthEnd.fileYear(uow, 2024);
                for (int month = 1; month < 10; month++) {
                    MonthEnd.closePeriod(uow, code(month), "office", LocalDate.of(2024, 10, 1));
                }
                for (String number : List.of("A-10441", "A-10442")) {
                    ScaleHouse.recordLoad(
                            uow, cornTicket.toBuilder().ticketNumber(number).build(), "MERRICK", rules);
                }
                uow.commit();
            }
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                SettlementRun.settleProducer(uow, "MERRICK", "HOME", rules, HARVEST, SPOT);
                uow.commit();
            }
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                DayEnd.runClose(uow, HARVEST);
                uow.commit();
            }
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                for (int month = 10; month <= 12; month++) {
                    MonthEnd.closePeriod(uow, code(month), "office", LocalDate.of(2025, 1, 6));
                }
                uow.commit();
            }
        }

        static String code(int month) {
            return String.format(Locale.ROOT, "2024-%02d", month);
        }

        void reopen(String code) {
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                uow.ledger().period(code).setClosed(false);
                uow.commit();
            }
        }

        void fileTheChartOnly() {
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                uow.ledger().fileChart(standardChart());
                uow.commit();
            }
        }

        <T> T inUnit(Function<UnitOfWork, T> work) {
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                return work.apply(uow);
            }
        }

        YearClose review() {
            return inUnit(uow -> Closing.reviewYear(uow, 2024));
        }

        void closeTheYear() {
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                Closing.closeYear(uow, 2024, "office");
                uow.commit();
            }
        }
    }

    @Nested
    class Review extends OnFile {

        @Test
        void aFinishedYearReviewsClean() {
            house();
            YearClose found = review();
            assertThat(found.isClean()).isTrue();
            assertThat(found.entry()).isPresent();
            assertThat(found.revenue()).isPositive();
            assertThat(found.margin()).isEqualByComparingTo(found.revenue().subtract(found.expense()));
        }

        @Test
        void anOpenMonthStopsIt() {
            house();
            reopen("2024-12");
            YearClose found = review();
            assertThat(found.isClean()).isFalse();
            assertThat(found.outstanding().get(0)).contains("2024-12");
        }

        @Test
        void aYearNobodyFiledIsReportedMonthByMonth() {
            fileTheChartOnly();
            YearClose found = review();
            assertThat(found.isClean()).isFalse();
            assertThat(found.outstanding()).anyMatch(item -> item.contains("not on file"));
        }

        @Test
        void anImplausibleYearIsRefused() {
            house();
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                assertThatThrownBy(() -> Closing.reviewYear(uow, 1899))
                        .isInstanceOf(ClosingError.class)
                        .hasMessageContaining("implausible");
            }
        }

        @Test
        void theDestinationsAreNamed() {
            house();
            YearClose found = review();
            assertThat(found.destinations())
                    .extracting(Destination::accountCode)
                    .containsExactly("3100");
            assertThat(found.destinations().get(0).dollars()).isEqualByComparingTo(found.margin());
        }
    }

    @Nested
    class ClosingTheYear extends OnFile {

        @Test
        void theYearCloses() {
            house();
            YearClose found;
            try (Capture log = Capture.start();
                    UnitOfWork uow = UnitOfWork.open(database)) {
                found = Closing.closeYear(uow, 2024, "R. Merrick");
                uow.commit();
                assertThat(log.find("year.closed"))
                        .singleElement()
                        .asString()
                        .contains("year=2024", "by=\"R. Merrick\"", "entry=Y-202412-0001");
            }
            assertThat(found.isClosed()).isTrue();
            boolean posted = inUnit(uow -> uow.ledger().isPosted(Closing.SOURCE, "2024"));
            assertThat(posted).isTrue();
        }

        @Test
        void theAccountsReadZeroAfterIt() {
            house();
            closeTheYear();
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                ChartOfAccounts books = uow.ledger().chart();
                List<JournalEntry> entries = uow.ledger().entriesBetween(LocalDate.of(2024, 1, 1), NEW_YEAR);
                TrialBalance after = trialBalance(books, entries, NEW_YEAR);
                assertThat(Closing.accountsLeftOpen(books, after)).isEmpty();
                assertThat(after.balances()).isTrue();
            }
        }

        @Test
        void closingTwiceIsRefused() {
            house();
            closeTheYear();
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                assertThatThrownBy(() -> Closing.closeYear(uow, 2024, "office"))
                        .isInstanceOf(ConflictError.class)
                        .hasMessageContaining("already been closed");
            }
        }

        @Test
        void aYearWithAnOpenMonthWillNotClose() {
            house();
            reopen("2024-11");
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                assertThatThrownBy(() -> Closing.closeYear(uow, 2024, "office"))
                        .isInstanceOf(ConflictError.class)
                        .hasMessageContaining("will not close");
            }
        }

        @Test
        void forceClosesItAnyway() {
            house();
            reopen("2024-11");
            YearClose found;
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                found = Closing.closeYear(uow, 2024, "office", Closing.RETAINED_EARNINGS, true);
                uow.commit();
            }
            assertThat(found.isClosed()).isTrue();
            assertThat(found.isClean()).isFalse();
        }
    }

    @Nested
    class OpeningBalances extends OnFile {

        @Test
        void theBalanceSheetAccountsCarryForward() {
            house();
            closeTheYear();
            Map<String, BigDecimal> carried = inUnit(uow -> Closing.openingBalances(uow, 2025));
            assertThat(carried).containsKey("1210");
            assertThat(carried.get(Closing.RETAINED_EARNINGS)).isPositive();
        }

        @Test
        void revenueAndExpenseAreNotCarried() {
            house();
            closeTheYear();
            Map<String, BigDecimal> carried = inUnit(uow -> Closing.openingBalances(uow, 2025));
            assertThat(carried.keySet()).noneMatch(code -> code.startsWith("4") || code.startsWith("5"));
        }

        @Test
        void retainedEarningsIsAlwaysPresent() {
            fileTheChartOnly();
            Map<String, BigDecimal> carried = inUnit(uow -> Closing.openingBalances(uow, 2025));
            assertThat(carried).isEqualTo(Map.of(Closing.RETAINED_EARNINGS, d("0.00")));
        }
    }

    @Nested
    class Render extends OnFile {

        @Test
        void itNamesTheYearAndTheMargin() {
            house();
            String page = Closing.render(review());
            assertThat(page).contains("YEAR END 2024", "margin", "would close clean");
        }

        @Test
        void aClosedYearSaysSo() {
            house();
            String page = inUnit(uow -> Closing.render(Closing.closeYear(uow, 2024, "office")));
            assertThat(page).contains("closed");
        }

        @Test
        void whatIsOutstandingIsListed() {
            house();
            reopen("2024-11");
            String page = Closing.render(review());
            assertThat(page).contains("outstanding", "2024-11");
        }
    }

    @Nested
    class AccountsLeftOpen {

        @Test
        void itFindsOneThatWasMissed() {
            assertThat(Closing.accountsLeftOpen(chart, balance)).containsExactlyInAnyOrder("4100", "4200", "5200");
        }

        @Test
        void itCanBeNarrowedToTheOnesAskedAbout() {
            assertThat(Closing.accountsLeftOpen(chart, balance, List.of("5200")))
                    .containsExactly("5200");
        }

        @Test
        void aClosedSetIsEmpty() {
            assertThat(Closing.accountsLeftOpen(chart, trialBalance(chart, List.of(), NEW_YEAR)))
                    .isEmpty();
        }
    }

    /** The same finished year, run from the command line. */
    @Nested
    class Commands extends OnFile {

        Shell shell;

        @BeforeEach
        void onFile() {
            house();
            shell = Shell.on(database);
        }

        @Test
        void reviewingTheYearWritesNothing() {
            Shell.Result found = shell.run("year-end", "2024");
            assertThat(found.code()).isZero();
            assertThat(found.output()).contains("would close clean");
            assertThat(shell.run("year-end", "2024").output()).contains("would close clean");
        }

        @Test
        void theYearCloses() {
            Shell.Result found = shell.run("close-year", "2024", "--by", "office");
            assertThat(found.code()).isZero();
            assertThat(found.output()).contains("YEAR END 2024");
            assertThat(shell.run("year-end", "2024").output()).contains("already been closed");
        }

        @Test
        void closingTwiceIsRefused() {
            shell.run("close-year", "2024", "--by", "office");
            Shell.Result again = shell.run("close-year", "2024", "--by", "office");
            assertThat(again.code()).isEqualTo(Main.REFUSED);
            assertThat(again.err()).contains("conflict: 2024 has already been closed");
        }

        @Test
        void theBooksReadZeroAfterTheCommand() {
            shell.run("close-year", "2024", "--by", "office");
            assertThat(shell.run("income", "2024-01-01", "2024-12-31").output()).contains("0.00");
        }

        @Test
        void itCanBeToldADifferentEquityAccount() {
            // 3000 is a heading, and a heading takes no posting.
            Shell.Result found = shell.run("close-year", "2024", "--by", "office", "--retained", "3000");
            assertThat(found.code()).isEqualTo(Main.REFUSED);
            assertThat(found.err()).contains("ledger_error: 3000");
        }
    }
}
