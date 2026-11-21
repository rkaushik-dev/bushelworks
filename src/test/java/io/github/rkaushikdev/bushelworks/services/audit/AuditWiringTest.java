package io.github.rkaushikdev.bushelworks.services.audit;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.HouseFixtures;
import io.github.rkaushikdev.bushelworks.domain.contracts.ContractKind;
import io.github.rkaushikdev.bushelworks.domain.contracts.DeliveryWindow;
import io.github.rkaushikdev.bushelworks.domain.contracts.PurchaseContract;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.settlement.Settlement;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.services.scalehouse.ScaleHouse;
import io.github.rkaushikdev.bushelworks.services.settlement.NothingToSettle;
import io.github.rkaushikdev.bushelworks.services.settlement.RunOptions;
import io.github.rkaushikdev.bushelworks.services.settlement.SettlementRun;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What ends up in the evidence table when the ordinary things happen. */
class AuditWiringTest {

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

    static PurchaseContract aContract() {
        return PurchaseContract.builder()
                .number("F-2024-118")
                .kind(ContractKind.FORWARD)
                .partyId("MERRICK")
                .commodityCode("CORN")
                .locationCode("HOME")
                .writtenOn(LocalDate.of(2024, 5, 14))
                .bushels(d("20000"))
                .window(new DeliveryWindow(LocalDate.of(2024, 9, 1), LocalDate.of(2024, 11, 30)))
                .flatPrice(d("4.6200"))
                .build();
    }

    List<Entry> trail(String documentKind, String documentNumber) {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            return Audit.forDocument(uow, documentKind, documentNumber);
        }
    }

    long entries() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            return Audit.count(uow);
        }
    }

    @Test
    void recordingALoadLeavesOneTicketEntry() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules, "rmerrick");
            uow.commit();
        }
        List<Entry> entries = trail(Document.TICKET, "A-10441");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).action()).isEqualTo(Action.RECORDED);
        assertThat(entries.get(0).actor()).isEqualTo("rmerrick");
        assertThat(entries.get(0).detail())
                .hasValueSatisfying(detail -> assertThat(detail).contains("grade 1"));
    }

    @Test
    void anAppliedLoadAlsoMarksTheContract() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.contracts().add(aContract());
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            uow.commit();
        }
        List<Entry> entries = trail(Document.CONTRACT, "F-2024-118");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).action()).isEqualTo(Action.APPLIED);
        assertThat(entries.get(0).detail())
                .hasValueSatisfying(detail -> assertThat(detail).contains("A-10441"));
    }

    @Test
    void anUnappliedLoadLeavesTheContractAlone() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            uow.commit();
        }
        assertThat(trail(Document.CONTRACT, "F-2024-118")).isEmpty();
    }

    @Test
    void settlingLeavesAnEntryWithTheNet() {
        Settlement statement;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            statement = SettlementRun.settleProducer(
                    uow,
                    "MERRICK",
                    "HOME",
                    rules,
                    SETTLED,
                    SPOT,
                    RunOptions.defaults().withActor("office"));
            uow.commit();
        }
        List<Entry> entries = trail(Document.SETTLEMENT, statement.number());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).amount()).contains(statement.net());
        assertThat(entries.get(0).actor()).isEqualTo("office");
        assertThat(entries.get(0).detail())
                .hasValueSatisfying(detail -> assertThat(detail).contains("A-10441"));
    }

    @Test
    void aProducersTrailHoldsBothKinds() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            SettlementRun.settleProducer(uow, "MERRICK", "HOME", rules, SETTLED, SPOT);
            uow.commit();
        }
        List<String> kinds;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            kinds = Audit.forParty(uow, "MERRICK").stream()
                    .map(Entry::documentKind)
                    .toList();
        }
        assertThat(kinds).contains(Document.TICKET, Document.SETTLEMENT);
    }

    @Test
    void twoLoadsLeaveTwoTicketEntries() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            ScaleHouse.recordLoad(
                    uow, cornTicket.toBuilder().ticketNumber("A-10442").build(), "MERRICK", rules);
            uow.commit();
        }
        assertThat(entries()).isEqualTo(2);
    }

    @Test
    void aRolledBackLoadLeavesNothing() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
        }
        assertThat(entries()).isZero();
    }

    @Test
    void aRefusedSettlementLeavesNothing() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> SettlementRun.settleProducer(uow, "HOLLIS", "HOME", rules, SETTLED, SPOT))
                    .isInstanceOf(NothingToSettle.class);
            uow.rollback();
        }
        assertThat(entries()).isZero();
    }

    @Test
    void theEntryCarriesTheBusinessDateNotTheClock() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            uow.commit();
        }
        Optional<LocalDate> effective;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            effective = uow
                    .session()
                    .createQuery("select a.effectiveOn from AuditRow a order by a.id", LocalDate.class)
                    .getResultList()
                    .stream()
                    .findFirst();
        }
        assertThat(effective).contains(LocalDate.of(2024, 10, 3));
    }

    @Test
    void aSplitLoadRecordsTheApplyingProducer() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.contracts().add(aContract());
            ScaleHouse.recordLoad(uow, cornTicket, "MERRICK", rules);
            uow.commit();
        }
        assertThat(trail(Document.CONTRACT, "F-2024-118").get(0).partyCode()).contains("MERRICK");
    }
}
