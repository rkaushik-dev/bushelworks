package io.github.rkaushikdev.bushelworks.services.legacy;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.services.legacy.LegacyLoader.loadSummary;
import static io.github.rkaushikdev.bushelworks.services.legacy.LegacyLoader.toTicket;
import static io.github.rkaushikdev.bushelworks.services.legacy.LegacyReader.parseFile;
import static io.github.rkaushikdev.bushelworks.services.legacy.LegacyReader.parseLine;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.bushelworks.HouseFixtures;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.tickets.Disposition;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Writing the old system's file into the ticket file. */
class LegacyLoadTest {

    /**
     * The old file has no BCFM column and never had one, so every load in the migration supplies the
     * house long run average once.
     */
    static final Map<Factor, BigDecimal> ASSUME = Map.of(Factor.BCFM, d("1.2"));

    Database database;
    RuleBook rules;

    @BeforeEach
    void freshDatabase() {
        database = TestDatabase.fresh();
        rules = HouseFixtures.rules();
    }

    static String aLine(String... overrides) {
        return OldLine.text("20241014", overrides);
    }

    static String aFile(String... lines) {
        return String.join("\n", lines.length == 0 ? new String[] {aLine()} : lines) + "\n";
    }

    Loaded loadFile(UnitOfWork uow, String text) {
        return loadFile(uow, text, true);
    }

    Loaded loadFile(UnitOfWork uow, String text, boolean skipExisting) {
        return LegacyLoader.load(uow, parseFile(text), rules, skipExisting, ASSUME);
    }

    @Test
    void aRowBecomesATicket() {
        ScaleTicket ticket = toTicket(parseLine(aLine(), 1));
        assertThat(ticket.ticketNumber()).isEqualTo("A-10441");
        assertThat(ticket.scaleDate()).isEqualTo(LocalDate.of(2024, 10, 14));
        assertThat(ticket.disposition()).isSameAs(Disposition.CASH_SALE);
        assertThat(ticket.grossPounds()).isEqualByComparingTo("78420");
    }

    @Test
    void theWholeLoadGoesToThePartyOnIt() {
        ScaleTicket ticket = toTicket(parseLine(aLine(), 1));
        assertThat(ticket.splits()).hasSize(1);
        assertThat(ticket.splits().get(0).partyId()).isEqualTo("MRK001");
        assertThat(ticket.splits().get(0).percent()).isEqualByComparingTo("100");
    }

    @Test
    void theSampleCarriesWhatTheOldFileHad() {
        ScaleTicket ticket = toTicket(parseLine(aLine(), 1));
        assertThat(ticket.sample().get(Factor.MOISTURE)).isEqualTo(d("18.2"));
        assertThat(ticket.sample().get(Factor.TEST_WEIGHT)).isEqualTo(d("56.5"));
        assertThat(ticket.sample().get(Factor.DAMAGE_TOTAL)).isEqualTo(d("1.0"));
    }

    @Test
    void aBlankMoistureIsLeftOutRatherThanSentAsZero() {
        ScaleTicket ticket = toTicket(parseLine(aLine("moisture", "      "), 1));
        assertThat(ticket.sample()).doesNotContainKey(Factor.MOISTURE);
    }

    @Test
    void theRemarkSaysWhereItCameFrom() {
        assertThat(toTicket(parseLine(aLine(), 7)).remarks()).contains("line 7");
    }

    @Test
    void aVoidedRowStaysVoidedOnTheTicket() {
        assertThat(toTicket(parseLine(aLine("void", "V"), 1)).voided()).isTrue();
    }

    @Test
    void aCleanFileLands() {
        Loaded loaded;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            loaded = loadFile(uow, aFile());
            uow.commit();
        }
        assertThat(loaded.written()).containsExactly("A-10441");
        assertThat(loaded.isClean()).isTrue();
    }

    @Test
    void theTicketIsOnFileAfterwards() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            loadFile(uow, aFile());
            uow.commit();
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(uow.tickets().exists("HOME", "A-10441")).isTrue();
        }
    }

    @Test
    void nothingIsWrittenWhenTheCallerDoesNotCommit() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(loadFile(uow, aFile()).written()).as("it was written").containsExactly("A-10441");
        }
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            assertThat(uow.tickets().exists("HOME", "A-10441")).isFalse();
        }
    }

    @Test
    void aVoidedRowIsSkipped() {
        Loaded loaded;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            loaded = loadFile(uow, aFile(aLine("void", "V")));
        }
        assertThat(loaded.written()).isEmpty();
        assertThat(loaded.skipped().get(0).reason()).isEqualTo("voided in the old system");
    }

    /**
     * Intake refuses a voided ticket outright, so there is no way in. The loader skips it first rather
     * than letting the refusal come back as an error, because a hundred thousand rows of "ticket is
     * voided" in a migration log bury the failures that mean something.
     */
    @Test
    void aVoidedRowIsSkippedBeforeIntakeCanRefuseIt() {
        Loaded loaded;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            loaded = loadFile(uow, aFile(aLine("void", "V")));
        }
        assertThat(loaded.skipped().get(0).reason()).isEqualTo("voided in the old system");
    }

    @Test
    void aSecondPassSkipsWhatTheFirstOneWrote() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            loadFile(uow, aFile());
            uow.commit();
        }
        Loaded again;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            again = loadFile(uow, aFile());
        }
        assertThat(again.written()).isEmpty();
        assertThat(again.skipped().get(0).reason()).isEqualTo("already on file");
    }

    @Test
    void aSecondPassCanBeToldToTryAnyway() {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            loadFile(uow, aFile());
            uow.commit();
        }
        Loaded again;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            again = loadFile(uow, aFile(), false);
        }
        assertThat(again.written()).isEmpty();
        assertThat(again.skipped().get(0).reason().toLowerCase(Locale.ROOT)).contains("already");
        assertThat(again.skipped().get(0).reason()).isNotEqualTo("already on file");
    }

    @Test
    void aRowTheDomainRefusesIsKeptNotLost() {
        String bad = aLine("ticket", "A-10442   ", "commodity", "OATS");
        Loaded loaded;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            loaded = loadFile(uow, aFile(aLine(), bad));
        }
        assertThat(loaded.written()).containsExactly("A-10441");
        assertThat(loaded.skipped()).hasSize(1);
    }

    @Test
    void theRestOfTheFileStillLandsAfterABadRow() {
        String bad = aLine("ticket", "A-10442   ", "commodity", "OATS");
        String good = aLine("ticket", "A-10443   ");
        Loaded loaded;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            loaded = loadFile(uow, aFile(bad, good));
        }
        assertThat(loaded.written()).containsExactly("A-10443");
    }

    @Test
    void aCleanLoadSummarisesToOneLine() {
        Loaded loaded;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            loaded = loadFile(uow, aFile());
        }
        assertThat(loadSummary(loaded)).isEqualTo("1 tickets written, none skipped");
    }

    @Test
    void aDirtyLoadNamesTheReasons() {
        Loaded loaded;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            loaded = loadFile(uow, aFile(aLine("void", "V")));
        }
        assertThat(loadSummary(loaded)).contains("voided in the old system");
    }

    /**
     * Eleven years of files, and the shrink tables only go back so far. The house filed its first
     * schedule at go live; anything older grades against nothing, and the loader has to say so per row
     * rather than stop, because the run is one file a night for eleven years.
     */
    @Test
    void aFileOlderThanEveryScheduleIsKeptNotLost() {
        Loaded loaded;
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            loaded = loadFile(uow, aFile(aLine("scale_date", "20140312")));
        }
        assertThat(loaded.written()).isEmpty();
        assertThat(loaded.skipped().get(0).reason()).contains("schedule");
    }
}
