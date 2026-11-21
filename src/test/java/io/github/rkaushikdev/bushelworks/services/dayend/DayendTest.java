package io.github.rkaushikdev.bushelworks.services.dayend;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.domain.ledger.Ledger.standardChart;
import static io.github.rkaushikdev.bushelworks.domain.ledger.Ledger.trialBalance;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.HouseFixtures;
import io.github.rkaushikdev.bushelworks.domain.ledger.TrialBalance;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.JournalEntryRow;
import io.github.rkaushikdev.bushelworks.persistence.models.JournalLegRow;
import io.github.rkaushikdev.bushelworks.persistence.models.PeriodRow;
import io.github.rkaushikdev.bushelworks.services.scalehouse.ScaleHouse;
import io.github.rkaushikdev.bushelworks.services.settlement.SettlementRun;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DayendTest {

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

    void loads(String... numbers) {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            for (String number : numbers) {
                ScaleHouse.recordLoad(
                        uow, cornTicket.toBuilder().ticketNumber(number).build(), "MERRICK", rules);
            }
            uow.commit();
        }
    }

    /** Three consecutive corn loads for MERRICK at HOME on harvest day. */
    void aDay() {
        loads("A-10441", "A-10442", "A-10443");
    }

    static PeriodRow aPeriod(String code, LocalDate startsOn, LocalDate endsOn) {
        PeriodRow period = new PeriodRow();
        period.setCode(code);
        period.setStartsOn(startsOn);
        period.setEndsOn(endsOn);
        return period;
    }

    /** The same day, with a chart and the two months around it on file: September closed, October open. */
    void books() {
        aDay();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.ledger().fileChart(standardChart());
            PeriodRow september = aPeriod("2024-09", LocalDate.of(2024, 9, 1), LocalDate.of(2024, 9, 30));
            september.setClosed(true);
            september.setClosedOn(LocalDate.of(2024, 10, 8));
            september.setClosedBy("office");
            uow.ledger()
                    .addPeriods(List.of(
                            september, aPeriod("2024-10", LocalDate.of(2024, 10, 1), LocalDate.of(2024, 10, 31))));
            uow.commit();
        }
    }

    void settleMerrick() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            SettlementRun.settleProducer(uow, "MERRICK", "HOME", rules, HARVEST, SPOT);
            uow.commit();
        }
    }

    Close closeHarvest() {
        return closeHarvest(CloseOptions.defaults());
    }

    Close closeHarvest(CloseOptions options) {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            return DayEnd.runClose(uow, HARVEST, options);
        }
    }

    Close closeHarvestAndCommit() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Close close = DayEnd.runClose(uow, HARVEST);
            uow.commit();
            return close;
        }
    }

    static CloseOptions tolerance(String bushels) {
        return CloseOptions.defaults().withPositionTolerance(d(bushels));
    }

    static List<String> kinds(Close close) {
        return close.discrepancies().stream().map(Discrepancy::kind).toList();
    }

    @Test
    void anEmptyDayClosesClean() {
        Close close = closeHarvest();
        assertThat(close.isClean()).isTrue();
        assertThat(close.ticketsWritten()).isZero();
        assertThat(close.bushelsIn()).isEqualTo(d("0.0000"));
    }

    @Test
    void theCloseCountsTheTickets() {
        aDay();
        Close close = closeHarvest();
        assertThat(close.ticketsWritten()).isEqualTo(3);
        assertThat(close.ticketsVoided()).isZero();
    }

    @Test
    void aVoidedTicketIsCountedSeparately() {
        aDay();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.tickets().voidTicket("HOME", "A-10442", "reweighed");
            uow.commit();
        }
        Close close = closeHarvest();
        assertThat(close.ticketsWritten()).isEqualTo(2);
        assertThat(close.ticketsVoided()).isEqualTo(1);
    }

    @Test
    void theCloseCountsTheBushelsThatCameIn() {
        aDay();
        assertThat(closeHarvest().bushelsIn()).isEqualTo(d("2798.5714"));
    }

    @Test
    void nothingWentOutOnADayWithNoShipments() {
        aDay();
        Close close = closeHarvest();
        assertThat(close.bushelsOut()).isEqualTo(d("0.0000"));
        assertThat(close.netBushels()).isEqualByComparingTo(close.bushelsIn());
    }

    @Test
    void theCloseCountsTheSettlements() {
        aDay();
        settleMerrick();
        Close close = closeHarvest();
        assertThat(close.settlementsWritten()).isEqualTo(1);
        assertThat(close.settledValue()).isPositive();
    }

    @Test
    void aConsecutiveRunOfTicketsClosesClean() {
        aDay();
        assertThat(closeHarvest().isClean()).isTrue();
    }

    @Test
    void aHoleInTheTicketNumbersIsFound() {
        loads("A-10441", "A-10444");
        Close close = closeHarvest();
        assertThat(close.isClean()).isFalse();
        assertThat(kinds(close)).contains("ticket numbering");
    }

    @Test
    void theHoleNamesTheMissingNumbers() {
        loads("A-10441", "A-10444");
        String detail = closeHarvest().discrepancies().get(0).detail();
        assertThat(detail).contains("10442");
        assertThat(detail).contains("10443");
    }

    @Test
    void oneTicketCannotHaveAHole() {
        loads("A-10441");
        assertThat(closeHarvest().isClean()).isTrue();
    }

    @Test
    void aSettlementThatDoesNotBalanceIsFound() {
        aDay();
        settleMerrick();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.settlements().row("S-2024-0001").setNet(d("1.00"));
            uow.commit();
        }
        Close close = closeHarvest();
        assertThat(close.isClean()).isFalse();
        assertThat(kinds(close)).contains("settlement");
    }

    @Test
    void aGrossThatDoesNotMatchItsLinesIsFound() {
        aDay();
        settleMerrick();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.settlements().row("S-2024-0001").setGross(d("99.99"));
            uow.commit();
        }
        assertThat(closeHarvest().discrepancies())
                .anySatisfy(item -> assertThat(item.detail()).contains("does not match its lines"));
    }

    @Test
    void aLargeOvernightPositionMoveIsFlagged() {
        aDay();
        assertThat(kinds(closeHarvest(tolerance("100")))).contains("position");
    }

    @Test
    void aQuietNightIsNotFlagged() {
        aDay();
        assertThat(kinds(closeHarvest(tolerance("250000")))).doesNotContain("position");
    }

    @Test
    void aCleanCloseCanBeSignedOff() {
        aDay();
        Close close = closeHarvest();
        assertThatCode(() -> DayEnd.requireClean(close)).doesNotThrowAnyException();
    }

    @Test
    void aDirtyCloseRefusesToBeSignedOff() {
        aDay();
        Close close = closeHarvest(tolerance("100"));
        assertThatThrownBy(() -> DayEnd.requireClean(close)).isInstanceOf(ConflictError.class);
    }

    @Test
    void theClosePrintsForTheDayBook() {
        aDay();
        String text = DayEnd.render(closeHarvest());
        assertThat(text).contains("DAILY CLOSE 2024-10-03");
        assertThat(text).contains("tickets written");
        assertThat(text).contains("closed clean");
    }

    @Test
    void aDirtyClosePrintsItsProblems() {
        aDay();
        String text = DayEnd.render(closeHarvest(tolerance("100")));
        assertThat(text).contains("problems:");
        assertThat(text).contains("position");
    }

    @Test
    void theCloseIsScopedToASite() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            ScaleHouse.recordLoad(
                    uow,
                    cornTicket.toBuilder()
                            .ticketNumber("A-20001")
                            .locationCode("LURAY")
                            .build(),
                    "MERRICK",
                    rules);
            uow.commit();
        }
        Close home = closeHarvest(CloseOptions.defaults().withLocationCode("HOME"));
        Close luray = closeHarvest(CloseOptions.defaults().withLocationCode("LURAY"));
        assertThat(home.ticketsWritten()).isEqualTo(1);
        assertThat(luray.ticketsWritten()).isEqualTo(1);
    }

    @Test
    void theClosePostsTheDaysSettlements() {
        books();
        settleMerrick();
        Close close = closeHarvestAndCommit();
        assertThat(close.isClean()).isTrue();
        assertThat(close.entriesPosted()).hasSize(1);
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(uow.ledger().isPosted("settlement", "S-2024-0001")).isTrue();
        }
    }

    @Test
    void theBooksBalanceAfterTheClose() {
        books();
        settleMerrick();
        closeHarvestAndCommit();
        TrialBalance balance;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            balance = trialBalance(uow.ledger().chart(), uow.ledger().entriesBetween(HARVEST, HARVEST), HARVEST);
        }
        assertThat(balance.balances()).isTrue();
        assertThat(balance.balanceOf("2100")).isPositive();
    }

    @Test
    void runningTheCloseTwiceDoesNotPostTwice() {
        books();
        settleMerrick();
        Close close = null;
        for (int run = 0; run < 2; run++) {
            close = closeHarvestAndCommit();
        }
        assertThat(close.entriesPosted()).isEmpty();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(uow.ledger().forSource("settlement", "S-2024-0001")).hasSize(1);
        }
    }

    @Test
    void aDryRunWritesNothing() {
        books();
        settleMerrick();
        Close close;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            close = DayEnd.runClose(uow, HARVEST, CloseOptions.defaults().withDryRun(true));
            uow.commit();
        }
        assertThat(close.isClean()).isTrue();
        assertThat(close.entriesPosted()).isEmpty();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(uow.ledger().isPosted("settlement", "S-2024-0001")).isFalse();
        }
    }

    @Test
    void aClosedMonthStopsTheClose() {
        books();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            SettlementRun.settleProducer(uow, "MERRICK", "HOME", rules, HARVEST, SPOT);
            PeriodRow october = uow.ledger().period("2024-10");
            october.setClosed(true);
            october.setClosedOn(LocalDate.of(2024, 11, 8));
            uow.commit();
        }
        Close close = closeHarvest();
        assertThat(close.isClean()).isFalse();
        assertThat(kinds(close)).contains("period");
    }

    @Test
    void aHouseWithNoPeriodsOnFileSaysSo() {
        aDay();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.ledger().fileChart(standardChart());
            SettlementRun.settleProducer(uow, "MERRICK", "HOME", rules, HARVEST, SPOT);
            uow.commit();
        }
        assertThat(closeHarvest().discrepancies())
                .anySatisfy(item -> assertThat(item.detail()).contains("nothing to post into"));
    }

    @Test
    void aSettlementThatWillNotReconcileIsNeverPosted() {
        books();
        settleMerrick();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.settlements().row("S-2024-0001").setNet(d("1.00"));
            uow.commit();
        }
        Close close = closeHarvestAndCommit();
        assertThat(close.entriesPosted()).isEmpty();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(uow.ledger().isPosted("settlement", "S-2024-0001")).isFalse();
        }
    }

    @Test
    void aHandWrittenEntryThatDoesNotBalanceIsFound() {
        books();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            JournalEntryRow row = new JournalEntryRow();
            row.setNumber("J-BACKFILL");
            row.setPostedOn(HARVEST);
            row.setMemo("keyed by hand");
            row.addLeg(aLeg(1, "1210", "debit", "100.00"));
            row.addLeg(aLeg(2, "2100", "credit", "90.00"));
            uow.session().persist(row);
            uow.commit();
        }
        assertThat(kinds(closeHarvest())).contains("journal");
    }

    static JournalLegRow aLeg(int sequence, String accountCode, String side, String dollars) {
        JournalLegRow leg = new JournalLegRow();
        leg.setSequence(sequence);
        leg.setAccountCode(accountCode);
        leg.setSide(side);
        leg.setDollars(d(dollars));
        return leg;
    }

    @Test
    void theClosePrintsHowManyEntriesItPosted() {
        books();
        settleMerrick();
        assertThat(DayEnd.render(closeHarvestAndCommit())).contains("entries posted");
    }

    @Test
    void theDayBookPageIsPrintedLineForLine() {
        aDay();
        assertThat(DayEnd.render(closeHarvest(tolerance("100"))))
                .isEqualTo(String.join(
                        "\n",
                        "                          BUSHELWORKS ELEVATOR",
                        "                       DAILY CLOSE 2024-10-03  *",
                        "========================================================================",
                        "tickets written                        3",
                        "tickets voided                         0",
                        "bushels in                     2798.5714",
                        "bushels out                       0.0000",
                        "net bushels                    2798.5714",
                        "settlements written                    0",
                        "settled value                       0.00",
                        "entries posted                         0",
                        "------------------------------------------------------------------------",
                        "CORN                           2798.5714 net long",
                        "------------------------------------------------------------------------",
                        "1 problems:",
                        "  position: CORN moved 2798.5714 bushels overnight, above the 100 tolerance"));
    }
}
