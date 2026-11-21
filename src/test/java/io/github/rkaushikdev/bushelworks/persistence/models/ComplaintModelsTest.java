package io.github.rkaushikdev.bushelworks.persistence.models;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.persistence.TestDatabase.assertIntegrityError;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.repositories.ComplaintNotFound;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The complaint tables and the register over them. */
class ComplaintModelsTest {

    static final LocalDate DELIVERED = LocalDate.of(2024, 10, 3);
    static final LocalDate RAISED = LocalDate.of(2024, 10, 9);

    Database database;

    @BeforeEach
    void freshDatabase() {
        database = TestDatabase.fresh();
    }

    static ComplaintRow aRow() {
        return aRow(row -> {});
    }

    static ComplaintRow aRow(Consumer<ComplaintRow> overrides) {
        ComplaintRow row = new ComplaintRow();
        row.setReference("C-2024-018");
        row.setSource("buyer");
        row.setSubject("car ran 15.8 moisture against a 14.0 contract");
        row.setRaisedOn(RAISED);
        row.setDeliveredOn(DELIVERED);
        row.setPartyCode("CARGILL");
        row.setBinId("BIN-3");
        row.setProbeId("P-88213");
        row.setOutcome("open");
        row.setCredit(d("0.00"));
        row.setNote("");
        overrides.accept(row);
        return row;
    }

    @Test
    void aComplaintCanBeFiled() {
        ComplaintRow row;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            row = uow.complaints().add(aRow());
            uow.commit();
        }
        assertThat(row.getId()).isNotNull();
    }

    @Test
    void aFiledComplaintCanBeReadBack() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.complaints().add(aRow());
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(uow.complaints().row("C-2024-018").getPartyCode()).contains("CARGILL");
        }
    }

    @Test
    void askingForOneThatIsNotThereIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> uow.complaints().row("C-NOPE"))
                    .isInstanceOf(ComplaintNotFound.class)
                    .hasMessage("no complaint C-NOPE");
        }
    }

    @Test
    void theSameReferenceTwiceIsAConflict() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.complaints().add(aRow());
            assertThatThrownBy(() -> uow.complaints().add(aRow()))
                    .isInstanceOf(ConflictError.class)
                    .hasMessage("C-2024-018 is already on the register");
        }
    }

    @Test
    void aNegativeCreditIsRefusedByTheDatabase() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertIntegrityError(() -> {
                uow.complaints().add(aRow(row -> row.setCredit(d("-1.00"))));
                uow.flush();
            });
        }
    }

    @Test
    void aComplaintBeforeTheDeliveryIsRefusedByTheDatabase() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertIntegrityError(() -> {
                uow.complaints().add(aRow(row -> row.setRaisedOn(LocalDate.of(2024, 10, 1))));
                uow.flush();
            });
        }
    }

    @Test
    void aComplaintNeedsNoBinOrTicket() {
        ComplaintRow row;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            row = uow.complaints().add(aRow(complaint -> {
                complaint.setReference("C-2");
                complaint.setBinId(null);
                complaint.setTicketNumber(null);
                complaint.setPartyCode(null);
            }));
            uow.commit();
        }
        assertThat(row.getBinId()).isEmpty();
    }

    @Test
    void theOpenListLeavesOutTheClosed() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.complaints().add(aRow());
            uow.complaints().add(aRow(row -> {
                row.setReference("C-2");
                row.setOutcome("upheld");
            }));
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(uow.complaints().openComplaints())
                    .extracting(ComplaintRow::getReference)
                    .containsExactly("C-2024-018");
        }
    }

    @Test
    void theOpenListIsOldestFirst() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.complaints().add(aRow());
            uow.complaints().add(aRow(row -> {
                row.setReference("C-2");
                row.setRaisedOn(LocalDate.of(2024, 10, 4));
            }));
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(uow.complaints().openComplaints())
                    .extracting(ComplaintRow::getReference)
                    .containsExactly("C-2", "C-2024-018");
        }
    }

    @Test
    void theOpenListCanBeNarrowedToABin() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.complaints().add(aRow());
            uow.complaints().add(aRow(row -> {
                row.setReference("C-2");
                row.setBinId("BIN-7");
            }));
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(uow.complaints().openComplaints("BIN-7"))
                    .extracting(ComplaintRow::getReference)
                    .containsExactly("C-2");
        }
    }

    @Test
    void aSpanPicksUpWhatFallsInsideIt() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.complaints().add(aRow());
            uow.complaints().add(aRow(row -> {
                row.setReference("C-2");
                row.setRaisedOn(LocalDate.of(2024, 12, 1));
            }));
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(uow.complaints().between(LocalDate.of(2024, 10, 1), LocalDate.of(2024, 10, 31)))
                    .extracting(ComplaintRow::getReference)
                    .containsExactly("C-2024-018");
        }
    }

    @Test
    void complaintsCanBeFoundByParty() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.complaints().add(aRow());
            uow.complaints().add(aRow(row -> {
                row.setReference("C-2");
                row.setPartyCode("MERRICK");
            }));
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(uow.complaints().forParty("MERRICK"))
                    .extracting(ComplaintRow::getReference)
                    .containsExactly("C-2");
        }
    }

    @Test
    void theBinCountsGroupByBin() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.complaints().add(aRow());
            uow.complaints().add(aRow(row -> row.setReference("C-2")));
            uow.complaints().add(aRow(row -> {
                row.setReference("C-3");
                row.setBinId("BIN-7");
            }));
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(uow.complaints().binCounts(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)))
                    .isEqualTo(Map.of("BIN-3", 2, "BIN-7", 1));
        }
    }

    @Test
    void aComplaintWithNoBinIsLeftOutOfTheCounts() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.complaints().add(aRow(row -> row.setBinId(null)));
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(uow.complaints().binCounts(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)))
                    .isEmpty();
        }
    }

    @Test
    void anActionIsNumberedFromOne() {
        ComplaintActionRow action;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.complaints().add(aRow());
            action = uow.complaints().act("C-2024-018", RAISED, "dee", "pulled the retained sample");
            uow.commit();
        }
        assertThat(action.getSequence()).isEqualTo(1);
    }

    @Test
    void actionsNumberInTheOrderTheyAreTaken() {
        ComplaintActionRow second;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.complaints().add(aRow());
            uow.complaints().act("C-2024-018", RAISED, "dee", "pulled the sample");
            second = uow.complaints().act("C-2024-018", LocalDate.of(2024, 10, 11), "dee", "reran it on the meter");
            uow.commit();
        }
        assertThat(second.getSequence()).isEqualTo(2);
    }

    @Test
    void theActionsHangOffTheComplaint() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.complaints().add(aRow());
            uow.complaints().act("C-2024-018", RAISED, "dee", "pulled the sample");
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(uow.complaints().row("C-2024-018").getActions())
                    .singleElement()
                    .hasToString("2024-10-09 dee: pulled the sample");
        }
    }

    @Test
    void actingOnAComplaintThatIsNotThereIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> uow.complaints().act("C-NOPE", RAISED, "dee", "nothing"))
                    .isInstanceOf(ComplaintNotFound.class);
        }
    }

    @Test
    void deletingAComplaintTakesItsActionsWithIt() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ComplaintRow row = uow.complaints().add(aRow());
            uow.complaints().act("C-2024-018", RAISED, "dee", "pulled the sample");
            uow.commit();
            uow.session().remove(row);
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> uow.complaints().row("C-2024-018")).isInstanceOf(ComplaintNotFound.class);
            assertThat(TestDatabase.count(uow.session(), ComplaintActionRow.class))
                    .isZero();
        }
    }

    @Test
    void theCreditsAddUpOverASpan() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.complaints().add(aRow(row -> {
                row.setOutcome("upheld");
                row.setCredit(d("412.50"));
                row.setClosedOn(LocalDate.of(2024, 10, 14));
            }));
            uow.complaints().add(aRow(row -> {
                row.setReference("C-2");
                row.setOutcome("settled");
                row.setCredit(d("87.25"));
                row.setClosedOn(LocalDate.of(2024, 10, 20));
            }));
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(uow.complaints().creditsBetween(LocalDate.of(2024, 10, 1), LocalDate.of(2024, 10, 31)))
                    .isEqualByComparingTo("499.75");
        }
    }

    @Test
    void aCreditOutsideTheSpanDoesNotCount() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.complaints().add(aRow(row -> {
                row.setOutcome("upheld");
                row.setCredit(d("412.50"));
                row.setClosedOn(LocalDate.of(2024, 12, 1));
            }));
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(uow.complaints().creditsBetween(LocalDate.of(2024, 10, 1), LocalDate.of(2024, 10, 31)))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Test
    void theRegisterIsNotAvailableOutsideTheBlock() {
        UnitOfWork uow = new UnitOfWork(database);
        assertThatThrownBy(() -> uow.complaints().openComplaints())
                .isInstanceOf(ConflictError.class)
                .hasMessage("the complaint repository are only available inside an open unit of work");
    }
}
