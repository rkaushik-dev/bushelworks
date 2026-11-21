package io.github.rkaushikdev.bushelworks.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.bushelworks.persistence.models.SettlementLineRow;
import io.github.rkaushikdev.bushelworks.persistence.models.SettlementRow;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The statement counter the query tests lean on has to count, and count the right tables. */
class CounterTest {

    Database database;

    @BeforeEach
    void freshDatabase() {
        database = TestDatabase.fresh();
    }

    @Test
    void itCountsTheStatementsRunWhileItIsArmed() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Counter counter = Counter.during(database, () -> {
                TestDatabase.all(uow.session(), SettlementRow.class);
                TestDatabase.all(uow.session(), SettlementLineRow.class);
            });
            assertThat(counter.count()).isEqualTo(2);
            TestDatabase.all(uow.session(), SettlementRow.class);
            assertThat(counter.count()).as("disarmed when the work returns").isEqualTo(2);
        }
    }

    @Test
    void aTableIsMatchedOnAWordBoundary() {
        try (UnitOfWork uow = UnitOfWork.open(database);
                Counter counter = Counter.start(database)) {
            uow.settlements().inWindow(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));
            uow.settlements().lineTotals(List.of(1, 2));
            assertThat(counter.count()).isEqualTo(2);
            assertThat(counter.selecting("settlement")).isEqualTo(1);
            assertThat(counter.selecting("settlement_line")).isEqualTo(1);
            assertThat(counter.selecting("SETTLEMENT_LINE")).isEqualTo(1);
            assertThat(counter.selecting("settlement_ticket")).isZero();
            assertThat(counter.statements()).hasSize(2);
        }
    }

    @Test
    void askingForNothingQueriesNothing() {
        try (UnitOfWork uow = UnitOfWork.open(database);
                Counter counter = Counter.start(database)) {
            assertThat(uow.settlements().lineTotals(List.of())).isEmpty();
            assertThat(uow.settlements().ticketCounts(List.of())).isEmpty();
            assertThat(counter.count()).isZero();
        }
    }
}
