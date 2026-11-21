package io.github.rkaushikdev.bushelworks.services.payments;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.HouseFixtures;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.services.audit.Audit;
import io.github.rkaushikdev.bushelworks.services.audit.Document;
import io.github.rkaushikdev.bushelworks.services.audit.Entry;
import io.github.rkaushikdev.bushelworks.services.scalehouse.ScaleHouse;
import io.github.rkaushikdev.bushelworks.services.settlement.SettlementRun;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PaymentsTest {

    static final LocalDate SETTLED = LocalDate.of(2024, 10, 20);
    static final LocalDate PAID = LocalDate.of(2024, 10, 24);
    static final BigDecimal SPOT = new BigDecimal("4.3500");

    Database database;
    RuleBook rules;
    ScaleTicket cornTicket;

    /** One corn load each for MERRICK and HOLLIS, each settled onto a statement of its own. */
    @BeforeEach
    void settled() {
        database = TestDatabase.fresh();
        rules = HouseFixtures.rules();
        cornTicket = HouseFixtures.cornTicket();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            ScaleHouse.recordLoad(
                    uow, cornTicket.toBuilder().ticketNumber("A-10442").build(), "HOLLIS", rules);
            SettlementRun.settleProducer(uow, "MERRICK", "HOME", rules, SETTLED, SPOT);
            SettlementRun.settleProducer(uow, "HOLLIS", "HOME", rules, SETTLED, SPOT);
            uow.commit();
        }
    }

    ChequeRun runAndCommit() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ChequeRun run = Payments.runCheques(uow, PAID, "010441");
            uow.commit();
            return run;
        }
    }

    void merrickOwesTheHouse() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.settlements().row("S-2024-0001").setNet(d("-250.00"));
            uow.commit();
        }
    }

    static Map<String, BigDecimal> clearedAsWritten(ChequeRun run) {
        return run.cheques().stream()
                .collect(Collectors.toMap(Cheque::chequeNumber, Cheque::dollars, (a, b) -> b, HashMap::new));
    }

    @Test
    void chequeNumbersKeepTheirLeadingZeroes() {
        assertThat(Payments.nextChequeNumber("010441", 1)).isEqualTo("010442");
        assertThat(Payments.nextChequeNumber("010441", 0)).isEqualTo("010441");
    }

    @Test
    void aChequeNumberThatWouldGrowPastTheStockIsRefused() {
        assertThatThrownBy(() -> Payments.nextChequeNumber("9999", 1)).isInstanceOf(PaymentError.class);
    }

    @Test
    void aChequeNumberHasToBeDigits() {
        assertThatThrownBy(() -> Payments.nextChequeNumber("A-1", 1)).isInstanceOf(PaymentError.class);
    }

    @Test
    void theUnpaidPileGroupsByProducer() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Payments.unpaidByParty(uow).keySet()).containsExactlyInAnyOrder("HOLLIS", "MERRICK");
        }
    }

    @Test
    void aRunWritesOneChequePerProducer() {
        ChequeRun run = runAndCommit();
        assertThat(run.count()).isEqualTo(2);
        assertThat(run.cheques()).extracting(Cheque::chequeNumber).containsExactly("010441", "010442");
    }

    @Test
    void theChequesComeOutInProducerOrder() {
        ChequeRun run = runAndCommit();
        assertThat(run.cheques()).extracting(Cheque::partyCode).containsExactly("HOLLIS", "MERRICK");
    }

    @Test
    void aPaidSettlementDropsOffThePile() {
        runAndCommit();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Payments.unpaidByParty(uow)).isEmpty();
        }
    }

    @Test
    void runningTwiceFindsNothingTheSecondTime() {
        runAndCommit();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> Payments.runCheques(uow, PAID, "010443")).isInstanceOf(PaymentError.class);
        }
    }

    @Test
    void twoSettlementsForOneProducerShareACheque() {
        database = TestDatabase.fresh();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            SettlementRun.settleProducer(uow, "MERRICK", "HOME", rules, SETTLED, SPOT);
            ScaleHouse.recordLoad(
                    uow, cornTicket.toBuilder().ticketNumber("A-10442").build(), "MERRICK", rules);
            SettlementRun.settleProducer(uow, "MERRICK", "HOME", rules, LocalDate.of(2024, 10, 21), SPOT);
            uow.commit();
        }
        ChequeRun run = runAndCommit();
        assertThat(run.count()).isEqualTo(1);
        assertThat(run.cheques().get(0).isMultiple()).isTrue();
        assertThat(run.cheques().get(0).settlementNumbers()).hasSize(2);
    }

    @Test
    void aRunCanBeLimitedToNamedProducers() {
        ChequeRun run;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            run = Payments.runCheques(
                    uow, PAID, "010441", ChequeOptions.defaults().withPartyCodes(List.of("MERRICK")));
            uow.commit();
        }
        assertThat(run.count()).isEqualTo(1);
        assertThat(run.cheques().get(0).partyCode()).isEqualTo("MERRICK");
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Payments.unpaidByParty(uow).keySet()).containsExactly("HOLLIS");
        }
    }

    @Test
    void namingAProducerWithNothingUnpaidIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> Payments.runCheques(
                            uow, PAID, "010441", ChequeOptions.defaults().withPartyCodes(List.of("BROCKMAN"))))
                    .isInstanceOf(PaymentError.class);
        }
    }

    @Test
    void aRunWithNothingToPayIsRefused() {
        Database empty = TestDatabase.fresh();
        try (UnitOfWork uow = UnitOfWork.open(empty)) {
            assertThatThrownBy(() -> Payments.runCheques(uow, PAID, "010441")).isInstanceOf(PaymentError.class);
        }
    }

    @Test
    void theRunTotalsWhatItWrote() {
        ChequeRun run = runAndCommit();
        BigDecimal written = run.cheques().stream().map(Cheque::dollars).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(run.total()).isEqualByComparingTo(written);
    }

    @Test
    void aChequeLeavesAnAuditEntry() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Payments.runCheques(uow, PAID, "010441", ChequeOptions.defaults().withActor("bookkeeper"));
            uow.commit();
        }
        List<Entry> entries;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            entries = Audit.forDocument(uow, Document.PAYMENT, "010441");
        }
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).actor()).isEqualTo("bookkeeper");
    }

    @Test
    void voidingAChequePutsTheSettlementsBack() {
        runAndCommit();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Payments.voidCheque(uow, "010441", "printer chewed it");
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Payments.unpaidByParty(uow)).hasSize(1);
        }
    }

    @Test
    void voidingAChequeNobodyWroteIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> Payments.voidCheque(uow, "099999", "wrong number"))
                    .isInstanceOf(PaymentError.class);
        }
    }

    @Test
    void aVoidNeedsAReason() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Payments.runCheques(uow, PAID, "010441");
            assertThatThrownBy(() -> Payments.voidCheque(uow, "010441", "")).isInstanceOf(PaymentError.class);
        }
    }

    @Test
    void aProducerOwingTheHouseIsHeldRatherThanPaid() {
        merrickOwesTheHouse();
        ChequeRun run = runAndCommit();
        assertThat(run.held()).extracting(Balance::partyCode).containsExactly("MERRICK");
        assertThat(run.count()).isEqualTo(1);
    }

    @Test
    void debitBalancesAreReportedSeparately() {
        merrickOwesTheHouse();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Payments.debitBalances(uow)).containsExactly(new Balance("MERRICK", d("-250.00")));
        }
    }

    @Test
    void reconcilingAMatchingBankListFindsNothing() {
        Map<String, BigDecimal> cleared = clearedAsWritten(runAndCommit());
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Payments.reconcile(uow, cleared)).isEmpty();
        }
    }

    @Test
    void reconcilingFindsAChequeClearedForTheWrongAmount() {
        ChequeRun run = runAndCommit();
        Map<String, BigDecimal> cleared = clearedAsWritten(run);
        String first = run.cheques().get(0).chequeNumber();
        cleared.put(first, cleared.get(first).add(d("1.00")));
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Payments.reconcile(uow, cleared)).containsExactly(first);
        }
    }

    @Test
    void reconcilingFindsAChequeTheHouseNeverWrote() {
        runAndCommit();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Payments.reconcile(uow, Map.of("099999", d("100.00")))).containsExactly("099999");
        }
    }
}
