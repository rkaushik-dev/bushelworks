package io.github.rkaushikdev.bushelworks.persistence;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.HouseFixtures;
import io.github.rkaushikdev.bushelworks.QuickStatements;
import io.github.rkaushikdev.bushelworks.domain.settlement.Settlement;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.persistence.models.ScaleTicketRow;
import io.github.rkaushikdev.bushelworks.persistence.repositories.TicketNotFound;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** One transaction, all the repositories, and what happens when the block is left. */
class UnitofworkTest {

    Database database;
    ScaleTicket cornTicket;

    @BeforeEach
    void freshDatabase() {
        database = TestDatabase.fresh();
        cornTicket = HouseFixtures.cornTicket();
    }

    private Optional<ScaleTicketRow> storedTicket() {
        return database.inSession(session -> TestDatabase.first(session, ScaleTicketRow.class));
    }

    /** A unit of work that has been opened and closed again, with nothing done in it. */
    private UnitOfWork leftBehind() {
        UnitOfWork uow = new UnitOfWork(database);
        try (uow) {
            assertThat(uow.begin().isOpen()).isTrue();
        }
        return uow;
    }

    @Test
    void aCommittedUnitOfWorkPersists() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.tickets().add(cornTicket, "MERRICK");
            uow.commit();
        }
        assertThat(storedTicket()).isPresent();
    }

    @Test
    void leavingTheBlockWithoutCommittingRollsBack() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.tickets().add(cornTicket, "MERRICK");
        }
        assertThat(storedTicket()).isEmpty();
    }

    @Test
    void anExceptionRollsBackWhatWasWritten() {
        assertThatThrownBy(() -> {
                    try (UnitOfWork uow = UnitOfWork.open(database)) {
                        uow.tickets().add(cornTicket, "MERRICK");
                        throw new IllegalStateException("the printer jammed");
                    }
                })
                .isInstanceOf(IllegalStateException.class);
        assertThat(storedTicket()).isEmpty();
    }

    @Test
    void anExceptionAfterACommitLeavesTheCommitAlone() {
        assertThatThrownBy(() -> {
                    try (UnitOfWork uow = UnitOfWork.open(database)) {
                        uow.tickets().add(cornTicket, "MERRICK");
                        uow.commit();
                        throw new IllegalStateException("the printer jammed afterwards");
                    }
                })
                .isInstanceOf(IllegalStateException.class);
        assertThat(storedTicket()).isPresent();
    }

    @Test
    void theRepositoriesShareOneSession() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.tickets().add(cornTicket, "MERRICK");
            // not committed yet, but visible to another repository on the same session
            assertThat(uow.tickets().exists("HOME", "A-10441")).isTrue();
            assertThat(uow.contracts().session()).isSameAs(uow.session());
            assertThat(uow.ledger().session()).isSameAs(uow.session());
        }
    }

    @Test
    void theUnitOfWorkClosesItself() {
        UnitOfWork uow = UnitOfWork.open(database);
        try (uow) {
            uow.tickets().add(cornTicket, "MERRICK");
            uow.commit();
        }
        assertThat(uow.isOpen()).isFalse();
    }

    @Test
    void reachingForARepositoryOutsideTheBlockSaysSo() {
        UnitOfWork uow = leftBehind();
        assertThatThrownBy(() -> uow.tickets().exists("HOME", "A-10441"))
                .isInstanceOf(ConflictError.class)
                .hasMessageContaining("open unit of work");
    }

    @Test
    void reachingForTheSessionOutsideTheBlockSaysSo() {
        UnitOfWork uow = leftBehind();
        assertThatThrownBy(uow::session)
                .isInstanceOf(ConflictError.class)
                .hasMessage("the session are only available inside an open unit of work");
    }

    @Test
    void everyRepositoryIsReachableInsideTheBlock() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(uow.tickets()).isNotNull();
            assertThat(uow.contracts()).isNotNull();
            assertThat(uow.settlements()).isNotNull();
            assertThat(uow.parties()).isNotNull();
            assertThat(uow.complaints()).isNotNull();
            assertThat(uow.ledger()).isNotNull();
        }
    }

    @Test
    void committingAfterTheBlockIsRefused() {
        UnitOfWork uow = leftBehind();
        assertThatThrownBy(uow::commit)
                .isInstanceOf(ConflictError.class)
                .hasMessage("cannot commit a unit of work that is not open");
    }

    @Test
    void flushingAfterTheBlockIsRefused() {
        UnitOfWork uow = leftBehind();
        assertThatThrownBy(uow::flush)
                .isInstanceOf(ConflictError.class)
                .hasMessage("cannot flush a unit of work that is not open");
    }

    @Test
    void aUnitOfWorkCannotBeOpenedTwice() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(uow::begin)
                    .isInstanceOf(ConflictError.class)
                    .hasMessage("this unit of work is already open");
        }
    }

    @Test
    void itReportsWhetherItCommitted() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.tickets().add(cornTicket, "MERRICK");
            assertThat(uow.isCommitted()).isFalse();
            uow.commit();
            assertThat(uow.isCommitted()).isTrue();
            uow.rollback();
            assertThat(uow.isCommitted()).isFalse();
        }
    }

    @Test
    void aRollbackInsideTheBlockUndoesOnlyWhatFollowed() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.tickets().add(cornTicket, "MERRICK");
            uow.commit();
            uow.tickets().add(cornTicket.toBuilder().ticketNumber("A-10442").build(), "MERRICK");
            uow.rollback();
        }
        List<String> numbers = database.inSession(session -> TestDatabase.all(session, ScaleTicketRow.class).stream()
                .map(ScaleTicketRow::getTicketNumber)
                .toList());
        assertThat(numbers).containsExactly("A-10441");
    }

    @Test
    void aMissingTicketStillRaisesInsideAUnitOfWork() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> uow.tickets().get("HOME", "A-99999")).isInstanceOf(TicketNotFound.class);
        }
    }

    @Test
    void settlementsAndTicketsLandTogether() {
        Settlement statement;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            ScaleTicketRow row = uow.tickets().add(cornTicket, "MERRICK");
            statement = QuickStatements.settlementFor(
                            "S-1", cornTicket, d("4.35"), HouseFixtures.rules(), "MERRICK", LocalDate.of(2024, 10, 20))
                    .build();
            uow.settlements().add(statement, List.of(row.getId()));
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(uow.settlements().get("S-1").net()).isEqualByComparingTo(statement.net());
            assertThat(uow.tickets().exists("HOME", "A-10441")).isTrue();
            Integer settlementId = uow.settlements().row("S-1").getId();
            assertThat(uow.settlements().ticketCounts(List.of(settlementId))).containsEntry(settlementId, 1);
        }
    }

    @Test
    void aUnitOfWorkCanBeOpenedAgainAfterItCloses() {
        UnitOfWork uow = leftBehind();
        try (uow) {
            uow.begin().tickets().add(cornTicket, "MERRICK");
            uow.commit();
        }
        assertThat(storedTicket()).isPresent();
        assertThat(uow.isOpen()).isFalse();
    }
}
