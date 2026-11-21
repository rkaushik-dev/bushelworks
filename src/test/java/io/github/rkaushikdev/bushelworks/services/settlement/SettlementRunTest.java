package io.github.rkaushikdev.bushelworks.services.settlement;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.HouseFixtures;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.domain.contracts.ContractKind;
import io.github.rkaushikdev.bushelworks.domain.contracts.DeliveryWindow;
import io.github.rkaushikdev.bushelworks.domain.contracts.PurchaseContract;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.settlement.LineKind;
import io.github.rkaushikdev.bushelworks.domain.settlement.Settlement;
import io.github.rkaushikdev.bushelworks.domain.settlement.SettlementLine;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.ScaleTicketRow;
import io.github.rkaushikdev.bushelworks.services.charges.Advance;
import io.github.rkaushikdev.bushelworks.services.charges.Deduction;
import io.github.rkaushikdev.bushelworks.services.charges.SettlementRefused;
import io.github.rkaushikdev.bushelworks.services.charges.StorageCharge;
import io.github.rkaushikdev.bushelworks.services.scalehouse.ScaleHouse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SettlementRunTest {

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

    static PurchaseContract aContract(String bushels) {
        return PurchaseContract.builder()
                .number("F-2024-118")
                .kind(ContractKind.FORWARD)
                .partyId("MERRICK")
                .commodityCode("CORN")
                .locationCode("HOME")
                .writtenOn(LocalDate.of(2024, 5, 14))
                .bushels(d(bushels))
                .window(new DeliveryWindow(LocalDate.of(2024, 9, 1), LocalDate.of(2024, 11, 30)))
                .flatPrice(d("4.6200"))
                .build();
    }

    ScaleTicket ticket(String number) {
        return cornTicket.toBuilder().ticketNumber(number).build();
    }

    Settlement settle(UnitOfWork uow, String partyCode) {
        return SettlementRun.settleProducer(uow, partyCode, "HOME", rules, SETTLED, SPOT);
    }

    Settlement settle(UnitOfWork uow, RunOptions options) {
        return SettlementRun.settleProducer(uow, "MERRICK", "HOME", rules, SETTLED, SPOT, options);
    }

    @Test
    void oneLoadSettlesAtSpot() {
        Settlement statement;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            statement = settle(uow, "MERRICK");
            uow.commit();
        }
        assertThat(statement.number()).isEqualTo("S-2024-0001");
        assertThat(statement.gross()).isEqualTo(d("3904.70"));
        assertThatCode(statement::checkBalances).doesNotThrowAnyException();
    }

    @Test
    void twoLoadsShareOneStatement() {
        Settlement statement;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            ScaleHouse.recordLoad(uow, ticket("A-10442"), "MERRICK", rules);
            statement = settle(uow, "MERRICK");
            uow.commit();
        }
        assertThat(statement.ticketNumbers()).containsExactly("A-10441", "A-10442");
        assertThat(statement.ofKind(LineKind.GROSS)).hasSize(2);
        assertThat(statement.gross()).isEqualTo(d("7809.40"));
    }

    @Test
    void aContractedLoadSettlesAtTheContractPrice() {
        Settlement statement;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.contracts().add(aContract("20000"));
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            statement = settle(uow, "MERRICK");
            uow.commit();
        }
        assertThat(statement.gross()).isEqualTo(d("4147.06"));
    }

    @Test
    void aPartlyContractedLoadBlendsTheTwoPrices() {
        Settlement statement;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.contracts().add(aContract("500"));
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            statement = settle(uow, "MERRICK");
            uow.commit();
        }
        assertThat(statement.gross()).isGreaterThan(d("3904.70")).isLessThan(d("4147.06"));
    }

    @Test
    void settledTicketsDoNotSettleTwice() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            settle(uow, "MERRICK");
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> settle(uow, "MERRICK")).isInstanceOf(NothingToSettle.class);
        }
    }

    @Test
    void aProducerWithNothingWaitingIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> settle(uow, "HOLLIS")).isInstanceOf(NothingToSettle.class);
        }
    }

    @Test
    void onlyTheNamedTicketsAreSettled() {
        Settlement statement;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            ScaleHouse.recordLoad(uow, ticket("A-10442"), "MERRICK", rules);
            statement = settle(uow, RunOptions.defaults().withTicketNumbers(List.of("A-10441")));
            uow.commit();
        }
        assertThat(statement.ticketNumbers()).containsExactly("A-10441");
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(uow.tickets().awaitingSettlement("HOME"))
                    .extracting(ScaleTicketRow::getTicketNumber)
                    .containsExactly("A-10442");
        }
    }

    @Test
    void namingATicketThatIsNotWaitingIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            RunOptions options = RunOptions.defaults().withTicketNumbers(List.of("A-99999"));
            assertThatThrownBy(() -> settle(uow, options)).isInstanceOf(NothingToSettle.class);
        }
    }

    @Test
    void anotherProducersTicketsAreLeftAlone() {
        Settlement statement;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            ScaleHouse.recordLoad(uow, ticket("A-10442"), "HOLLIS", rules);
            statement = settle(uow, "MERRICK");
            uow.commit();
        }
        assertThat(statement.ticketNumbers()).containsExactly("A-10441");
    }

    @Test
    void anotherSitesTicketsAreLeftAlone() {
        Settlement statement;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            ScaleHouse.recordLoad(
                    uow,
                    cornTicket.toBuilder()
                            .ticketNumber("A-10442")
                            .locationCode("LURAY")
                            .build(),
                    "MERRICK",
                    rules);
            statement = settle(uow, "MERRICK");
            uow.commit();
        }
        assertThat(statement.ticketNumbers()).containsExactly("A-10441");
    }

    @Test
    void theCheckoffIsLeviedOnceAcrossTheStatement() {
        Settlement statement;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            ScaleHouse.recordLoad(uow, ticket("A-10442"), "MERRICK", rules);
            statement = settle(uow, "MERRICK");
            uow.commit();
        }
        List<SettlementLine> levies = statement.ofKind(LineKind.ASSESSMENT);
        assertThat(levies).hasSize(1);
        assertThat(levies.get(0).dollars()).isEqualTo(d("17.95"));
    }

    @Test
    void discountsAreItemisedPerTicket() {
        ScaleTicket dirty = cornTicket.toBuilder()
                .ticketNumber("A-10442")
                .sample(HouseFixtures.cornSampleWith(Factor.TEST_WEIGHT, "53.4"))
                .build();
        Settlement statement;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            ScaleHouse.recordLoad(uow, dirty, "MERRICK", rules);
            statement = settle(uow, "MERRICK");
            uow.commit();
        }
        assertThat(statement.ofKind(LineKind.DISCOUNT))
                .extracting(SettlementLine::code)
                .containsExactly("TEST_WEIGHT-A-10442");
    }

    @Test
    void advancesAndStorageComeThrough() {
        Settlement statement;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            statement = settle(
                    uow,
                    RunOptions.defaults()
                            .withAdvances(List.of(new Advance("A", d("1000.00"), LocalDate.of(2024, 9, 12))))
                            .withStorage(List.of(new StorageCharge("L-1", d("42.00"), SETTLED))));
            uow.commit();
        }
        assertThat(statement.subtotal(LineKind.ADVANCE)).isEqualTo(d("1000.00"));
        assertThat(statement.subtotal(LineKind.STORAGE)).isEqualTo(d("42.00"));
        assertThatCode(statement::checkBalances).doesNotThrowAnyException();
    }

    @Test
    void settlementNumbersRunConsecutively() {
        Settlement first;
        Settlement second;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            ScaleHouse.recordLoad(uow, ticket("A-10442"), "HOLLIS", rules);
            first = settle(uow, "MERRICK");
            second = settle(uow, "HOLLIS");
            uow.commit();
        }
        assertThat(first.number()).isEqualTo("S-2024-0001");
        assertThat(second.number()).isEqualTo("S-2024-0002");
    }

    @Test
    void anOtherDeductionLandsOnItsOwnLine() {
        Settlement statement;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            statement = settle(
                    uow,
                    RunOptions.defaults()
                            .withDeductions(List.of(new Deduction("FREIGHT", "freight to Salina", d("184.50")))));
            uow.commit();
        }
        List<SettlementLine> lines = statement.ofKind(LineKind.DEDUCTION);
        assertThat(lines).extracting(SettlementLine::code).containsExactly("FREIGHT");
        assertThat(lines.get(0).dollars()).isEqualTo(d("184.50"));
        assertThatCode(statement::checkBalances).doesNotThrowAnyException();
    }

    @Test
    void aDeductionOfNothingIsRefused() {
        assertThatThrownBy(() -> new Deduction("FREIGHT", "freight", d("0"))).isInstanceOf(SettlementRefused.class);
    }

    @Test
    void aDeductionNeedsADescription() {
        assertThatThrownBy(() -> new Deduction("FREIGHT", "", d("10.00"))).isInstanceOf(SettlementRefused.class);
    }

    @Test
    void deductionsComeBeforeAdvancesOnTheStatement() {
        Settlement statement;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            statement = settle(
                    uow,
                    RunOptions.defaults()
                            .withAdvances(List.of(new Advance("A", d("100.00"), LocalDate.of(2024, 9, 12))))
                            .withDeductions(List.of(new Deduction("FREIGHT", "freight", d("184.50")))));
            uow.commit();
        }
        List<LineKind> kinds =
                statement.lines().stream().map(SettlementLine::kind).toList();
        assertThat(kinds.indexOf(LineKind.ADVANCE)).isLessThan(kinds.indexOf(LineKind.DEDUCTION));
    }

    @Test
    void theStatementIsStoredAndReadsBack() {
        Settlement statement;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            statement = settle(uow, "MERRICK");
            uow.commit();
        }
        Settlement stored;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            stored = uow.settlements().get(statement.number());
        }
        assertThat(stored.net()).isEqualTo(statement.net());
    }
}
