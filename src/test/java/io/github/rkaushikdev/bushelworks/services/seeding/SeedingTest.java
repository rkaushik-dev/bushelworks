package io.github.rkaushikdev.bushelworks.services.seeding;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.HouseFixtures;
import io.github.rkaushikdev.bushelworks.domain.tickets.SplitShare;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.BinRow;
import io.github.rkaushikdev.bushelworks.persistence.models.CommodityRow;
import io.github.rkaushikdev.bushelworks.persistence.models.Location;
import io.github.rkaushikdev.bushelworks.persistence.models.Party;
import io.github.rkaushikdev.bushelworks.persistence.models.ScaleTicketRow;
import io.github.rkaushikdev.bushelworks.services.parties.Agreement;
import io.github.rkaushikdev.bushelworks.services.parties.Parties;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SeedingTest {

    Database database;

    @BeforeEach
    void freshDatabase() {
        database = TestDatabase.fresh();
    }

    /** A seeded house, committed, and what seeding said it put in. */
    Seeded seeded() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Seeded result = Seeding.seed(uow, HouseFixtures.rules());
            uow.commit();
            return result;
        }
    }

    List<ScaleTicketRow> tickets() {
        return database.inSession(session -> TestDatabase.all(session, ScaleTicketRow.class));
    }

    @Test
    void anEmptyDatabaseIsNotSeeded() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(Seeding.isSeeded(uow)).isFalse();
        }
    }

    @Test
    void seedingReportsWhatItPutIn() {
        Seeded result = seeded();
        assertThat(result.locations()).isEqualTo(2);
        assertThat(result.parties()).isEqualTo(6);
        assertThat(result.bins()).isEqualTo(6);
        assertThat(result.tickets()).isEqualTo(Seeding.LOADS.size());
        assertThat(result.isEmpty()).isFalse();
    }

    @Test
    void theSitesAreOnFile() {
        seeded();
        List<String> codes = database.inSession(session -> TestDatabase.all(session, Location.class)).stream()
                .map(Location::getCode)
                .sorted()
                .toList();
        assertThat(codes).containsExactly("HOME", "LURAY");
    }

    @Test
    void theCommodityMasterIsOnFile() {
        seeded();
        List<String> codes = database.inSession(session -> TestDatabase.all(session, CommodityRow.class)).stream()
                .map(CommodityRow::getCode)
                .sorted()
                .toList();
        assertThat(codes).containsExactly("CORN", "HRW", "MILO", "SOYB", "SUNF");
    }

    @Test
    void theProducersAreOnFile() {
        seeded();
        List<String> codes;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            codes = uow.parties().active().stream().map(Party::getCode).toList();
        }
        assertThat(codes).contains("MERRICK").hasSize(6);
    }

    @Test
    void theBinsAreOnFile() {
        seeded();
        List<Boolean> aerated = database.inSession(session -> TestDatabase.all(session, BinRow.class)).stream()
                .map(BinRow::isAerated)
                .toList();
        assertThat(aerated).hasSize(6).containsOnly(true);
    }

    @Test
    void theSplitAgreementIsFiled() {
        seeded();
        Agreement agreement;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            agreement = Parties.agreementFor(uow, "HOME-PLACE", LocalDate.of(2024, 10, 1));
        }
        assertThat(agreement.isBalanced()).isTrue();
        assertThat(agreement.shares()).extracting(SplitShare::partyId).containsExactlyInAnyOrder("BROCKMAN", "MERRICK");
    }

    @Test
    void theTicketsAreWritten() {
        seeded();
        long count = database.inSession(session -> TestDatabase.count(session, ScaleTicketRow.class));
        assertThat(count).isEqualTo(Seeding.LOADS.size());
    }

    @Test
    void theTicketsAreSpreadOverTheWeek() {
        seeded();
        Set<LocalDate> days =
                tickets().stream().map(ScaleTicketRow::getScaleDate).collect(Collectors.toSet());
        assertThat(days).hasSizeGreaterThan(1);
    }

    @Test
    void theTicketsAreSpreadOverTheSites() {
        seeded();
        Set<String> sites =
                tickets().stream().map(ScaleTicketRow::getLocationCode).collect(Collectors.toSet());
        assertThat(sites).containsExactlyInAnyOrder("HOME", "LURAY");
    }

    @Test
    void theTicketNumbersRunConsecutively() {
        seeded();
        List<String> numbers =
                tickets().stream().map(ScaleTicketRow::getTicketNumber).sorted().toList();
        assertThat(numbers.get(0)).isEqualTo("A-10441");
        assertThat(Set.copyOf(numbers)).hasSize(Seeding.LOADS.size());
    }

    @Test
    void seedingTwiceIsRefused() {
        seeded();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThatThrownBy(() -> Seeding.seed(uow, null)).isInstanceOf(ConflictError.class);
        }
    }

    @Test
    void seedingIsDeterministic() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Seeding.seed(uow, HouseFixtures.rules());
            uow.commit();
        }
        List<BigDecimal> weights;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            weights = uow
                    .session()
                    .createQuery("select t from ScaleTicketRow t order by t.ticketNumber", ScaleTicketRow.class)
                    .getResultList()
                    .stream()
                    .map(ScaleTicketRow::getGrossPounds)
                    .toList();
        }
        assertThat(weights.get(0)).isEqualTo(d("78420"));
    }

    @Test
    void theDescriptionIsOneLine() {
        String text = Seeding.describe(seeded());
        assertThat(text).doesNotContain("\n").contains("6 producers");
    }
}
