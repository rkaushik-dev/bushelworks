package io.github.rkaushikdev.bushelworks.services.audit;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuditTest {

    Database database;

    @BeforeEach
    void freshDatabase() {
        database = TestDatabase.fresh();
    }

    @Test
    void anEntryIsWrittenAndReadBack() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Audit.record(
                    uow,
                    Action.RECORDED,
                    Document.TICKET,
                    "A-10441",
                    Particulars.by("rmerrick").withPartyCode("MERRICK").withLocationCode("HOME"));
            uow.commit();
        }
        List<Entry> entries;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            entries = Audit.forDocument(uow, Document.TICKET, "A-10441");
        }
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).action()).isEqualTo("recorded");
        assertThat(entries.get(0).actor()).isEqualTo("rmerrick");
    }

    @Test
    void theActorDefaultsToTheSystem() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Audit.record(uow, Action.ACCRUED, Document.STORAGE_LOT, "L-1");
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Audit.forDocument(uow, Document.STORAGE_LOT, "L-1")
                            .get(0)
                            .actor())
                    .isEqualTo("system");
        }
    }

    @Test
    void anEntryWithoutAnActionIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> Audit.record(uow, "", Document.TICKET, "A-10441"))
                    .isInstanceOf(AuditError.class);
        }
    }

    @Test
    void anEntryWithoutADocumentNumberIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> Audit.record(uow, Action.RECORDED, Document.TICKET, ""))
                    .isInstanceOf(AuditError.class);
        }
    }

    @Test
    void aRolledBackTransactionLeavesNoEvidence() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Audit.record(uow, Action.RECORDED, Document.TICKET, "A-10441");
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Audit.count(uow)).isZero();
        }
    }

    @Test
    void entriesComeBackOldestFirst() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Audit.record(uow, Action.RECORDED, Document.TICKET, "A-10441");
            Audit.record(uow, Action.VOIDED, Document.TICKET, "A-10441");
            uow.commit();
        }
        List<String> actions;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            actions = Audit.forDocument(uow, Document.TICKET, "A-10441").stream()
                    .map(Entry::action)
                    .toList();
        }
        assertThat(actions).containsExactly("recorded", "voided");
    }

    @Test
    void theLastActionIsTheMostRecent() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Audit.record(uow, Action.RECORDED, Document.TICKET, "A-10441");
            Audit.record(uow, Action.VOIDED, Document.TICKET, "A-10441");
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Audit.lastAction(uow, Document.TICKET, "A-10441"))
                    .map(Entry::action)
                    .contains("voided");
        }
    }

    @Test
    void aDocumentNothingHappenedToHasNoLastAction() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Audit.lastAction(uow, Document.TICKET, "A-99999")).isEmpty();
        }
    }

    @Test
    void entriesForAProducerComeBackNewestFirst() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Audit.record(
                    uow,
                    Action.RECORDED,
                    Document.TICKET,
                    "A-1",
                    Particulars.none().withPartyCode("MERRICK"));
            Audit.record(
                    uow,
                    Action.SETTLED,
                    Document.SETTLEMENT,
                    "S-1",
                    Particulars.none().withPartyCode("MERRICK"));
            Audit.record(
                    uow,
                    Action.RECORDED,
                    Document.TICKET,
                    "A-2",
                    Particulars.none().withPartyCode("HOLLIS"));
            uow.commit();
        }
        List<Entry> entries;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            entries = Audit.forParty(uow, "MERRICK");
        }
        assertThat(entries).extracting(Entry::documentNumber).containsExactly("S-1", "A-1");
    }

    @Test
    void theProducerViewCanBeLimited() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            for (int index = 0; index < 5; index++) {
                Audit.record(
                        uow,
                        Action.RECORDED,
                        Document.TICKET,
                        "A-" + index,
                        Particulars.none().withPartyCode("MERRICK"));
            }
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Audit.forParty(uow, "MERRICK", 2)).hasSize(2);
        }
    }

    @Test
    void anAmountIsKeptAsItStood() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Audit.record(
                    uow,
                    Action.SETTLED,
                    Document.SETTLEMENT,
                    "S-2024-0417",
                    Particulars.none().withPartyCode("MERRICK").withAmount(d("2848.01")));
            uow.commit();
        }
        Entry entry;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            entry = Audit.forDocument(uow, Document.SETTLEMENT, "S-2024-0417").get(0);
        }
        assertThat(entry.amount()).contains(d("2848.01"));
    }

    @Test
    void aLongDetailIsTrimmedRatherThanRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Audit.record(
                    uow,
                    Action.VOIDED,
                    Document.TICKET,
                    "A-10441",
                    Particulars.none().withDetail("x".repeat(900)));
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Audit.forDocument(uow, Document.TICKET, "A-10441").get(0).detail())
                    .hasValueSatisfying(detail -> assertThat(detail).hasSize(400));
        }
    }

    @Test
    void theDayViewFindsTodaysEntries() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Audit.record(uow, Action.RECORDED, Document.TICKET, "A-1");
            Audit.record(uow, Action.VOIDED, Document.TICKET, "A-1");
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Audit.forDay(uow, today)).hasSize(2);
            assertThat(Audit.forDay(uow, today, Action.VOIDED)).hasSize(1);
            assertThat(Audit.forDay(uow, LocalDate.of(2000, 1, 1))).isEmpty();
        }
    }

    @Test
    void theCountIsWhatWasWritten() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            for (int index = 0; index < 3; index++) {
                Audit.record(uow, Action.RECORDED, Document.TICKET, "A-" + index);
            }
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Audit.count(uow)).isEqualTo(3);
        }
    }
}
