package io.github.rkaushikdev.bushelworks.services.numbering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NumberingTest {

    static final LocalDate OCTOBER = LocalDate.of(2024, 10, 20);

    Database database;

    @BeforeEach
    void freshDatabase() {
        database = TestDatabase.fresh();
    }

    @Test
    void aNumberRendersWithAPaddedSequence() {
        assertThat(new DocumentNumber("S", 2024, 417)).hasToString("S-2024-0417");
        assertThat(new DocumentNumber("S", 2024, 7)).hasToString("S-2024-0007");
        assertThat(new DocumentNumber("S", 2024, 12345)).hasToString("S-2024-12345");
    }

    @Test
    void aNumberParsesBack() {
        assertThat(DocumentNumber.parse("S-2024-0417")).isEqualTo(new DocumentNumber("S", 2024, 417));
    }

    @Test
    void parsingRejectsWhatIsNotANumber() {
        for (String bad : List.of("S2024417", "S-24-0417", "S-2024-abc", "-2024-0417", "S-2024")) {
            assertThatThrownBy(() -> DocumentNumber.parse(bad)).as(bad).isInstanceOf(NumberingError.class);
        }
    }

    @Test
    void nextStepsTheSequenceAndKeepsTheYear() {
        assertThat(new DocumentNumber("S", 2024, 417).next()).isEqualTo(new DocumentNumber("S", 2024, 418));
    }

    @Test
    void theFirstNumberOfASeriesIsOne() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Numbering numbering = new Numbering(uow.session());
            assertThat(numbering.allocate("S", OCTOBER)).hasToString("S-2024-0001");
        }
    }

    @Test
    void numbersRunConsecutively() {
        List<String> issued = new ArrayList<>();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Numbering numbering = new Numbering(uow.session());
            for (int index = 0; index < 3; index++) {
                issued.add(numbering.allocate("S", OCTOBER).toString());
            }
        }
        assertThat(issued).containsExactly("S-2024-0001", "S-2024-0002", "S-2024-0003");
    }

    @Test
    void theCounterRestartsEachYear() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Numbering numbering = new Numbering(uow.session());
            numbering.allocate("S", OCTOBER);
            numbering.allocate("S", OCTOBER);
            assertThat(numbering.allocate("S", LocalDate.of(2025, 1, 3))).hasToString("S-2025-0001");
        }
    }

    @Test
    void seriesDoNotShareACounter() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Numbering numbering = new Numbering(uow.session());
            numbering.allocate("S", OCTOBER);
            assertThat(numbering.allocate("ADV", OCTOBER)).hasToString("ADV-2024-0001");
        }
    }

    @Test
    void peekDoesNotConsumeANumber() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Numbering numbering = new Numbering(uow.session());
            assertThat(numbering.peek("S", OCTOBER)).isEqualTo(1);
            numbering.allocate("S", OCTOBER);
            assertThat(numbering.peek("S", OCTOBER)).isEqualTo(2);
            assertThat(numbering.peek("S", OCTOBER)).isEqualTo(2);
        }
    }

    @Test
    void aRollbackPutsTheNumberBack() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            new Numbering(uow.session()).allocate("S", OCTOBER);
            uow.rollback();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(new Numbering(uow.session()).allocate("S", OCTOBER)).hasToString("S-2024-0001");
        }
    }

    @Test
    void aCommittedNumberIsNotReissued() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            new Numbering(uow.session()).allocate("S", OCTOBER);
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(new Numbering(uow.session()).allocate("S", OCTOBER)).hasToString("S-2024-0002");
        }
    }

    @Test
    void aBlockOfNumbersComesBackInOrder() {
        List<DocumentNumber> block;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            block = new Numbering(uow.session()).reserveBlock("S", OCTOBER, 4);
        }
        assertThat(block).extracting(DocumentNumber::sequence).containsExactly(1, 2, 3, 4);
    }

    @Test
    void aBlockMovesTheCounterPastIt() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Numbering numbering = new Numbering(uow.session());
            numbering.reserveBlock("S", OCTOBER, 4);
            assertThat(numbering.allocate("S", OCTOBER)).hasToString("S-2024-0005");
        }
    }

    @Test
    void anEmptyBlockIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Numbering numbering = new Numbering(uow.session());
            assertThatThrownBy(() -> numbering.reserveBlock("S", OCTOBER, 0)).isInstanceOf(NumberingError.class);
        }
    }

    @Test
    void anUnnamedSeriesIsRefused() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Numbering numbering = new Numbering(uow.session());
            assertThatThrownBy(() -> numbering.allocate("", OCTOBER)).isInstanceOf(NumberingError.class);
        }
    }

    @Test
    void aGaplessYearPassesTheCheck() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Numbering numbering = new Numbering(uow.session());
            assertThatCode(() -> numbering.checkGapless("S", 2024, List.of(1, 2, 3, 4)))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void anEmptyYearPassesTheCheck() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Numbering numbering = new Numbering(uow.session());
            assertThatCode(() -> numbering.checkGapless("S", 2024, List.of())).doesNotThrowAnyException();
        }
    }

    @Test
    void aHoleInTheYearIsReported() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Numbering numbering = new Numbering(uow.session());
            assertThatThrownBy(() -> numbering.checkGapless("S", 2024, List.of(1, 2, 4, 5)))
                    .isInstanceOf(ConflictError.class)
                    .hasMessageContaining("missing 1 numbers: 3");
        }
    }
}
