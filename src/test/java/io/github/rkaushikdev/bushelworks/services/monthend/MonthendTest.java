package io.github.rkaushikdev.bushelworks.services.monthend;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.domain.ledger.Ledger.standardChart;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.Capture;
import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.HouseFixtures;
import io.github.rkaushikdev.bushelworks.domain.ledger.Account;
import io.github.rkaushikdev.bushelworks.domain.ledger.AccountType;
import io.github.rkaushikdev.bushelworks.domain.ledger.ChartOfAccounts;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.JournalEntryRow;
import io.github.rkaushikdev.bushelworks.persistence.models.JournalLegRow;
import io.github.rkaushikdev.bushelworks.persistence.models.PeriodRow;
import io.github.rkaushikdev.bushelworks.persistence.repositories.PeriodNotFound;
import io.github.rkaushikdev.bushelworks.services.dayend.Close;
import io.github.rkaushikdev.bushelworks.services.dayend.DayEnd;
import io.github.rkaushikdev.bushelworks.services.dayend.Discrepancy;
import io.github.rkaushikdev.bushelworks.services.scalehouse.ScaleHouse;
import io.github.rkaushikdev.bushelworks.services.settlement.SettlementRun;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Closing a month, and the three things that stop it.
 *
 * <p>The house posts one real settlement so the month has something in it. Every test that wants a
 * month to refuse breaks exactly one of the three checks, which is the only way to know which check
 * did the refusing.
 */
class MonthendTest {

    static final LocalDate HARVEST = LocalDate.of(2024, 10, 3);
    static final BigDecimal SPOT = new BigDecimal("4.35");

    Database database;
    RuleBook rules;
    ScaleTicket cornTicket;

    @BeforeEach
    void freshDatabase() {
        database = TestDatabase.fresh();
        rules = HouseFixtures.rules();
        cornTicket = HouseFixtures.cornTicket();
    }

    /**
     * A chart, a filed year, three loads and one settlement, all posted.
     *
     * <p>January through September are closed because they are empty and the house would have closed
     * them as it went. Leaving them open makes every October test fail on the sequence check instead
     * of on the thing it is testing.
     */
    void house() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.ledger().fileChart(standardChart());
            MonthEnd.fileYear(uow, 2024);
            for (int month = 1; month < 10; month++) {
                MonthEnd.closePeriod(uow, code(2024, month), "office", LocalDate.of(2024, 10, 1));
            }
            for (String number : List.of("A-10441", "A-10442", "A-10443")) {
                ScaleHouse.recordLoad(
                        uow, cornTicket.toBuilder().ticketNumber(number).build(), "MERRICK", rules);
            }
            uow.commit();
        }
        settleMerrick();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            DayEnd.runClose(uow, HARVEST);
            uow.commit();
        }
    }

    void settleMerrick() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            SettlementRun.settleProducer(uow, "MERRICK", "HOME", rules, HARVEST, SPOT);
            uow.commit();
        }
    }

    static String code(int year, int month) {
        return String.format(Locale.ROOT, "%d-%02d", year, month);
    }

    MonthClose review(String code) {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            return MonthEnd.review(uow, code);
        }
    }

    void closeOctober() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            MonthEnd.closePeriod(uow, "2024-10", "office", LocalDate.of(2024, 11, 6));
            uow.commit();
        }
    }

    Close closeHarvest() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            return DayEnd.runClose(uow, HARVEST);
        }
    }

    static List<String> kinds(MonthClose found) {
        return found.outstanding().stream().map(Outstanding::kind).toList();
    }

    static List<String> kinds(Close close) {
        return close.discrepancies().stream().map(Discrepancy::kind).toList();
    }

    @Nested
    class FilingAYear {

        @Test
        void twelveMonthsComeOut() {
            List<AccountingMonth> months = MonthEnd.monthsOf(2025);
            assertThat(months).hasSize(12);
            assertThat(months.get(0))
                    .isEqualTo(new AccountingMonth("2025-01", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 31)));
            assertThat(months.get(11))
                    .isEqualTo(new AccountingMonth("2025-12", LocalDate.of(2025, 12, 1), LocalDate.of(2025, 12, 31)));
        }

        @Test
        void februaryOfALeapYearHasTwentyNineDays() {
            AccountingMonth february = MonthEnd.monthsOf(2024).get(1);
            assertThat(february.code()).isEqualTo("2024-02");
            assertThat(february.endsOn()).isEqualTo(LocalDate.of(2024, 2, 29));
        }

        @Test
        void februaryOfAnOrdinaryYearHasTwentyEight() {
            assertThat(MonthEnd.monthsOf(2025).get(1).endsOn()).isEqualTo(LocalDate.of(2025, 2, 28));
        }

        @Test
        void everyMonthRunsToTheDayBeforeTheNext() {
            List<AccountingMonth> months = MonthEnd.monthsOf(2025);
            for (int index = 1; index < months.size(); index++) {
                assertThat(ChronoUnit.DAYS.between(
                                months.get(index - 1).endsOn(),
                                months.get(index).startsOn()))
                        .isEqualTo(1);
            }
        }

        @Test
        void aFiledYearIsOnFile() {
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                MonthEnd.fileYear(uow, 2025);
                uow.commit();
            }
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                assertThat(uow.ledger().openPeriods()).hasSize(12);
            }
        }

        @Test
        void filingTheSameYearTwiceIsRefused() {
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                MonthEnd.fileYear(uow, 2025);
                uow.commit();
            }
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                assertThatThrownBy(() -> MonthEnd.fileYear(uow, 2025))
                        .isInstanceOf(ConflictError.class)
                        .hasMessageContaining("already on file");
            }
        }

        @Test
        void anImplausibleYearIsRefused() {
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                assertThatThrownBy(() -> MonthEnd.fileYear(uow, 1899))
                        .isInstanceOf(MonthEndError.class)
                        .hasMessageContaining("implausible");
            }
        }
    }

    @Nested
    class Review {

        @Test
        void aFinishedMonthReviewsClean() {
            house();
            MonthClose found = review("2024-10");
            assertThat(found.isClean()).isTrue();
            assertThat(found.settlements()).isEqualTo(1);
            assertThat(found.entries()).isEqualTo(1);
            assertThat(found.unposted()).isEmpty();
        }

        @Test
        void theMonthCarriesItsOwnDates() {
            house();
            MonthClose found = review("2024-10");
            assertThat(found.startsOn()).isEqualTo(LocalDate.of(2024, 10, 1));
            assertThat(found.endsOn()).isEqualTo(LocalDate.of(2024, 10, 31));
        }

        @Test
        void aSettlementThatNeverPostedIsNamed() {
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                uow.ledger().fileChart(standardChart());
                MonthEnd.fileYear(uow, 2024);
                ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
                uow.commit();
            }
            settleMerrick();
            MonthClose found = review("2024-10");
            assertThat(found.isClean()).isFalse();
            assertThat(found.unposted()).containsExactly("S-2024-0001");
            assertThat(kinds(found)).contains("unposted");
        }

        @Test
        void anEarlierOpenMonthStopsIt() {
            house();
            MonthClose found = review("2024-11");
            assertThat(kinds(found)).contains("sequence");
            assertThat(found.outstanding().get(0).toString()).contains("2024-10 is still open");
        }

        @Test
        void januaryLooksAtTheDecemberBeforeIt() {
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                uow.ledger().fileChart(standardChart());
                MonthEnd.fileYear(uow, 2025);
                uow.commit();
            }
            assertThat(review("2025-01").isClean()).isTrue();
        }

        @Test
        void aJournalThatDoesNotBalanceIsFound() {
            house();
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                JournalEntryRow row = new JournalEntryRow();
                row.setNumber("J-HAND");
                row.setPostedOn(LocalDate.of(2024, 10, 20));
                row.setMemo("keyed by hand");
                row.addLeg(leg(1, "1210", "debit", "100.00"));
                row.addLeg(leg(2, "2100", "credit", "75.00"));
                uow.session().persist(row);
                uow.commit();
            }
            assertThat(kinds(review("2024-10"))).contains("journal");
        }

        @Test
        void aMonthNobodyFiledIsALookupFailure() {
            house();
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                assertThatThrownBy(() -> MonthEnd.review(uow, "2029-04")).isInstanceOf(PeriodNotFound.class);
            }
        }

        static JournalLegRow leg(int sequence, String account, String side, String dollars) {
            JournalLegRow leg = new JournalLegRow();
            leg.setSequence(sequence);
            leg.setAccountCode(account);
            leg.setSide(side);
            leg.setDollars(d(dollars));
            return leg;
        }
    }

    @Nested
    class Closing {

        @Test
        void aFinishedMonthCloses() {
            house();
            MonthClose found;
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                found = MonthEnd.closePeriod(uow, "2024-10", "R. Merrick", LocalDate.of(2024, 11, 6));
                uow.commit();
            }
            assertThat(found.isClosed()).isTrue();
            assertThat(found.isClean()).isTrue();
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                PeriodRow row = uow.ledger().period("2024-10");
                assertThat(row.isClosed()).isTrue();
                assertThat(row.getClosedBy()).isEqualTo("R. Merrick");
                assertThat(row.getClosedOn()).contains(LocalDate.of(2024, 11, 6));
            }
        }

        @Test
        void aClosedMonthIsNoLongerOpen() {
            house();
            closeOctober();
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                assertThat(uow.ledger().openPeriods())
                        .extracting(PeriodRow::getCode)
                        .doesNotContain("2024-10");
            }
        }

        @Test
        void closingTwiceIsRefused() {
            house();
            closeOctober();
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                assertThatThrownBy(() -> MonthEnd.closePeriod(uow, "2024-10", "office"))
                        .isInstanceOf(ConflictError.class)
                        .hasMessageContaining("already closed");
            }
        }

        @Test
        void aMonthWithSomethingOutstandingWillNotClose() {
            house();
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                assertThatThrownBy(() -> MonthEnd.closePeriod(uow, "2024-11", "office"))
                        .isInstanceOf(ConflictError.class)
                        .hasMessageContaining("2024-11 will not close");
            }
        }

        @Test
        void forceClosesItAnyway() {
            house();
            MonthClose found;
            try (Capture log = Capture.start();
                    UnitOfWork uow = UnitOfWork.open(database)) {
                found = MonthEnd.closePeriod(uow, "2024-11", "office", LocalDate.of(2024, 12, 3), true);
                uow.commit();
                assertThat(log.find("period.forced"))
                        .singleElement()
                        .asString()
                        .contains("period=2024-11", "outstanding=1");
            }
            assertThat(found.isClosed()).isTrue();
            assertThat(found.isClean()).isFalse();
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                assertThat(uow.ledger().period("2024-11").isClosed()).isTrue();
            }
        }

        @Test
        void nothingCanBePostedIntoAClosedMonth() {
            house();
            closeOctober();
            assertThat(kinds(closeHarvest())).contains("period");
        }
    }

    @Nested
    class Reopening {

        @Test
        void aClosedMonthCanBeOpenedAgainWithAReason() {
            house();
            closeOctober();
            try (Capture log = Capture.start();
                    UnitOfWork uow = UnitOfWork.open(database)) {
                PeriodRow row = MonthEnd.reopenPeriod(uow, "2024-10", "the co-op amended its return");
                uow.commit();
                assertThat(row.isClosed()).isFalse();
                assertThat(row.getClosedOn()).isEmpty();
                assertThat(row.getClosedBy()).isEmpty();
                assertThat(log.find("period.reopened"))
                        .singleElement()
                        .asString()
                        .contains("reason=\"the co-op amended its return\"");
            }
        }

        @Test
        void reopeningWithoutAReasonIsRefused() {
            house();
            closeOctober();
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                assertThatThrownBy(() -> MonthEnd.reopenPeriod(uow, "2024-10", "   "))
                        .isInstanceOf(MonthEndError.class)
                        .hasMessageContaining("needs a reason");
            }
        }

        @Test
        void aMonthThatIsOpenCannotBeReopened() {
            house();
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                assertThatThrownBy(() -> MonthEnd.reopenPeriod(uow, "2024-10", "no reason to"))
                        .isInstanceOf(ConflictError.class)
                        .hasMessageContaining("is not closed");
            }
        }

        @Test
        void aReopenedMonthTakesPostingsAgain() {
            house();
            closeOctober();
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                MonthEnd.reopenPeriod(uow, "2024-10", "a load was keyed late");
                uow.commit();
            }
            assertThat(kinds(closeHarvest())).doesNotContain("period");
        }
    }

    @Nested
    class WhereALateTicketGoes {

        @Test
        void theEarliestOpenMonthIsTheOne() {
            house();
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                assertThat(MonthEnd.earliestOpen(uow)).map(PeriodRow::getCode).contains("2024-10");
            }
        }

        @Test
        void itMovesOnAsMonthsClose() {
            house();
            closeOctober();
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                assertThat(MonthEnd.earliestOpen(uow)).map(PeriodRow::getCode).contains("2024-11");
            }
        }

        @Test
        void aHouseWithNoPeriodsHasNowhereToPutIt() {
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                Optional<PeriodRow> found = MonthEnd.earliestOpen(uow);
                assertThat(found).isEmpty();
            }
        }
    }

    @Nested
    class Margin {

        @Test
        void theMonthCarriesWhatItEarned() {
            house();
            MonthClose found = review("2024-10");
            assertThat(found.revenue()).isPositive();
            assertThat(found.margin()).isEqualByComparingTo(found.revenue().subtract(found.expense()));
        }

        @Test
        void aMonthWithNoBalanceReadsAsNothing() {
            MonthClose empty = new MonthClose("2024-01", HARVEST, HARVEST);
            assertThat(empty.revenue()).isEqualTo(d("0.00"));
            assertThat(empty.expense()).isEqualTo(d("0.00"));
            assertThat(empty.margin()).isEqualTo(d("0.00"));
        }

        @Test
        void theRevenueAccountsComeBackByCode() {
            house();
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                Map<String, BigDecimal> margins = MonthEnd.marginsByAccount(uow, "2024-10");
                assertThat(margins).containsKey("4100").containsKey("4000").doesNotContainKey("1210");
            }
        }

        @Test
        void theHeadingCarriesWhatItsAccountsEarned() {
            house();
            try (UnitOfWork uow = UnitOfWork.open(database)) {
                Map<String, BigDecimal> margins = MonthEnd.marginsByAccount(uow, "2024-10");
                ChartOfAccounts chart = uow.ledger().chart();
                BigDecimal leaves = chart.ofType(AccountType.REVENUE).stream()
                        .map(Account::code)
                        .filter(code -> !chart.isHeading(code))
                        .map(margins::get)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                assertThat(margins.get("4000")).isEqualByComparingTo(leaves);
            }
        }
    }
}
