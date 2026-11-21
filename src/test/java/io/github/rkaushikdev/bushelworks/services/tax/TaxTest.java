package io.github.rkaushikdev.bushelworks.services.tax;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.HouseFixtures;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.services.charges.Advance;
import io.github.rkaushikdev.bushelworks.services.scalehouse.ScaleHouse;
import io.github.rkaushikdev.bushelworks.services.settlement.RunOptions;
import io.github.rkaushikdev.bushelworks.services.settlement.SettlementRun;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaxTest {

    static final LocalDate SETTLED = LocalDate.of(2024, 10, 20);
    static final BigDecimal SPOT = d("4.3500");

    Database database;
    RuleBook rules;
    ScaleTicket cornTicket;

    @BeforeEach
    void freshDatabase() {
        database = TestDatabase.fresh();
        rules = HouseFixtures.rules();
        cornTicket = HouseFixtures.cornTicket();
    }

    /** MERRICK settled with a thousand dollar advance and HOLLIS without one, both in 2024. */
    void aYear() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            SettlementRun.settleProducer(
                    uow,
                    "MERRICK",
                    "HOME",
                    rules,
                    SETTLED,
                    SPOT,
                    RunOptions.defaults()
                            .withAdvances(List.of(new Advance("A", d("1000.00"), LocalDate.of(2024, 9, 12)))));
            ScaleHouse.recordLoad(
                    uow, cornTicket.toBuilder().ticketNumber("A-10442").build(), "HOLLIS", rules);
            SettlementRun.settleProducer(uow, "HOLLIS", "HOME", rules, SETTLED, SPOT);
            uow.commit();
        }
    }

    /** MERRICK alone, settled and committed, then the statement's gross line rewritten. */
    void oneStatementGrossing(String dollars) {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            SettlementRun.settleProducer(uow, "MERRICK", "HOME", rules, SETTLED, SPOT);
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.settlements().row("S-2024-0001").getLines().get(0).setDollars(d(dollars));
            uow.commit();
        }
    }

    @Test
    void aYearThatCannotBeReportedOn() {
        aYear();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> Tax.payeeYear(uow, "MERRICK", 1899)).isInstanceOf(TaxError.class);
        }
    }

    @Test
    void theReportableFigureIsGrossLessThePriceReductions() {
        aYear();
        PayeeYear payee;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            payee = Tax.payeeYear(uow, "MERRICK", 2024);
        }
        assertThat(payee.gross()).isEqualTo(d("3904.70"));
        assertThat(payee.reductions()).isPositive();
        assertThat(payee.reportable()).isEqualByComparingTo(payee.gross().subtract(payee.reductions()));
    }

    @Test
    void anAdvanceDoesNotReduceTheReportableFigure() {
        aYear();
        PayeeYear withAdvance;
        PayeeYear without;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            withAdvance = Tax.payeeYear(uow, "MERRICK", 2024);
            without = Tax.payeeYear(uow, "HOLLIS", 2024);
        }
        assertThat(withAdvance.advancesRecovered()).isEqualTo(d("1000.00"));
        assertThat(without.advancesRecovered()).isEqualTo(d("0.00"));
        assertThat(withAdvance.reportable()).isEqualByComparingTo(without.reportable());
    }

    @Test
    void theSettlementCountIsCarried() {
        aYear();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Tax.payeeYear(uow, "MERRICK", 2024).settlements()).isEqualTo(1);
        }
    }

    @Test
    void aProducerOverTheThresholdIsReportable() {
        aYear();
        PayeeYear payee;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            payee = Tax.payeeYear(uow, "MERRICK", 2024);
        }
        assertThat(payee.reportable()).isGreaterThan(Tax.REPORTING_THRESHOLD);
        assertThat(payee.isReportable()).isTrue();
        assertThat(payee.shortfall()).isEqualTo(d("0.00"));
    }

    @Test
    void aProducerWithNothingInTheYearIsEmpty() {
        aYear();
        PayeeYear payee;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            payee = Tax.payeeYear(uow, "MERRICK", 2023);
        }
        assertThat(payee.gross()).isEqualTo(d("0.00"));
        assertThat(payee.isReportable()).isFalse();
        assertThat(payee.shortfall()).isEqualTo(Tax.REPORTING_THRESHOLD);
    }

    @Test
    void theYearBoundaryIsRespected() {
        aYear();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Tax.payeeYear(uow, "MERRICK", 2025).settlements()).isZero();
        }
    }

    @Test
    void thePayeeListIsBiggestFirst() {
        aYear();
        List<PayeeYear> found;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            found = Tax.payees(uow, 2024);
        }
        assertThat(found).extracting(PayeeYear::partyCode).containsExactly("HOLLIS", "MERRICK");
        assertThat(found.get(0).reportable())
                .isGreaterThanOrEqualTo(found.get(1).reportable());
    }

    @Test
    void theReportableListDropsTheSmallOnes() {
        oneStatementGrossing("100.00");
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Tax.reportable(uow, 2024)).isEmpty();
        }
    }

    @Test
    void nearThresholdFindsTheOnesThatNearlyMadeIt() {
        oneStatementGrossing("650.00");
        List<PayeeYear> found;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            found = Tax.nearThreshold(uow, 2024, d("500.00"));
        }
        assertThat(found).extracting(PayeeYear::partyCode).containsExactly("MERRICK");
    }

    @Test
    void aVoidedSettlementIsLeftOut() {
        aYear();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.settlements().row("S-2024-0001").setVoided(true);
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Tax.payeeYear(uow, "MERRICK", 2024).settlements()).isZero();
        }
    }

    @Test
    void theWorkingFileHasARowForEverybody() {
        aYear();
        String text;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            text = Tax.workingFile(uow, 2024);
        }
        List<String> rows = List.of(text.strip().split("\n"));
        assertThat(rows.get(0)).startsWith("party_code,year,settlements");
        assertThat(rows).hasSize(3);
    }

    @Test
    void theWorkingFileSaysWhoIsReportable() {
        aYear();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Tax.workingFile(uow, 2024)).contains(",yes");
        }
    }

    @Test
    void anEmptyYearStillWritesAHeader() {
        aYear();
        String text;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            text = Tax.workingFile(uow, 2023);
        }
        assertThat(text.strip())
                .isEqualTo("party_code,year,settlements,gross,reductions,reportable,advances_recovered,is_reportable");
    }
}
