package io.github.rkaushikdev.bushelworks.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.bushelworks.Csv;
import io.github.rkaushikdev.bushelworks.HouseFixtures;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.reports.exports.Exports;
import io.github.rkaushikdev.bushelworks.services.houses.Houses;
import io.github.rkaushikdev.bushelworks.services.scalehouse.ScaleHouse;
import io.github.rkaushikdev.bushelworks.services.settlement.SettlementRun;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The three files the auditor asks for every February. */
class CliExportTest {

    static final LocalDate SETTLED = LocalDate.of(2024, 10, 20);
    static final BigDecimal SPOT = new BigDecimal("4.3500");
    static final String[] SPAN = {"--from", "2024-01-01", "--to", "2025-12-31"};

    @TempDir
    Path tmp;

    Database database;
    Shell shell;

    @BeforeEach
    void freshDatabase() {
        database = TestDatabase.fresh();
        shell = Shell.on(database);
    }

    /** Three corn loads for MERRICK, numbered A-10441 to A-10443. */
    void weighed() {
        weigh("MERRICK", 3);
    }

    void weigh(String party, int loads) {
        RuleBook rules = HouseFixtures.rules();
        ScaleTicket corn = HouseFixtures.cornTicket();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            for (int index = 0; index < loads; index++) {
                ScaleHouse.recordLoad(
                        uow,
                        corn.toBuilder().ticketNumber("A-" + (10441 + index)).build(),
                        party,
                        rules);
            }
            uow.commit();
        }
    }

    /** The three loads, settled on one statement. */
    void settled() {
        weighed();
        settle("MERRICK");
    }

    void settle(String party) {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            SettlementRun.settleProducer(uow, party, "HOME", Houses.houseRulebook(), SETTLED, SPOT);
            uow.commit();
        }
    }

    Shell.Result run(String... args) {
        return shell.run(args);
    }

    static String[] withSpan(String command, String... more) {
        return Stream.of(Stream.of(command), Stream.of(SPAN), Stream.of(more))
                .flatMap(each -> each)
                .toArray(String[]::new);
    }

    static List<List<String>> rows(String text) {
        return Csv.parse(text);
    }

    static List<List<String>> rowsIn(Path file) throws IOException {
        return rows(Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void anEmptyHouseStillWritesAHeader() {
        assertThat(rows(run("export-tickets").output()).get(0)).isEqualTo(Exports.TICKET_COLUMNS);
    }

    @Test
    void everyTicketGetsARow() {
        weighed();
        assertThat(rows(run("export-tickets").output())).hasSize(4);
    }

    @Test
    void theRowsAreInScaleDateOrder() {
        weighed();
        List<String> numbers = rows(run("export-tickets").output()).stream()
                .skip(1)
                .map(row -> row.get(0))
                .toList();
        assertThat(numbers).isSorted().hasSize(3);
    }

    @Test
    void aDateWindowLeavesTheRestOut() {
        weighed();
        assertThat(rows(run("export-tickets", "--from", "2025-01-01").output())).hasSize(1);
    }

    @Test
    void aSiteThatIsNotThereHasNothing() {
        weighed();
        assertThat(rows(run("export-tickets", "--location", "LURAY").output())).hasSize(1);
    }

    @Test
    void aProducerFilterNarrowsIt() {
        weighed();
        assertThat(rows(run("export-tickets", "--party", "MERRICK").output())).hasSize(4);
        assertThat(rows(run("export-tickets", "--party", "HOLLIS").output())).hasSize(1);
    }

    @Test
    void voidedTicketsAreLeftOutByDefault() {
        weighed();
        run("void", "HOME", "A-10441", "--reason", "keyed twice");
        assertThat(rows(run("export-tickets").output())).hasSize(3);
    }

    @Test
    void voidedTicketsComeBackWhenAskedFor() {
        weighed();
        run("void", "HOME", "A-10441", "--reason", "keyed twice");
        assertThat(rows(run("export-tickets", "--include-voided").output())).hasSize(4);
    }

    @Test
    void theFileCanBeWrittenToDisk() throws IOException {
        weighed();
        Path out = tmp.resolve("tickets.csv");
        Shell.Result result = run("export-tickets", "--out", out.toString());
        assertThat(result.output()).contains("3 tickets written");
        assertThat(rowsIn(out).get(0)).isEqualTo(Exports.TICKET_COLUMNS);
    }

    @Test
    void theWrittenFileHasNoCarriageReturns() throws IOException {
        weighed();
        Path out = tmp.resolve("tickets.csv");
        run("export-tickets", "--out", out.toString());
        byte[] written = Files.readAllBytes(out);
        assertThat(written).isNotEmpty().doesNotContain((byte) '\r');
    }

    @Test
    void aRemarkWithANewlineDoesNotBreakTheShape() throws IOException {
        weighed();
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            uow.tickets().row("HOME", "A-10441").setRemarks("driver says\nthe tarp was off");
            uow.commit();
        }
        Path out = tmp.resolve("tickets.csv");
        run("export-tickets", "--out", out.toString());
        assertThat(rowsIn(out)).hasSize(4);
    }

    @Test
    void theSettlementExportNeedsASpan() {
        settled();
        assertThat(run("export-settlements").code()).isEqualTo(Main.MISUSED);
    }

    @Test
    void aSettlementGetsOneRow() {
        settled();
        List<List<String>> rows = rows(run(withSpan("export-settlements")).output());
        assertThat(rows.get(0)).isEqualTo(Exports.SETTLEMENT_COLUMNS);
        assertThat(rows).hasSize(2);
    }

    @Test
    void aSpanThatMissesItWritesOnlyAHeader() {
        settled();
        Shell.Result result = run("export-settlements", "--from", "2023-01-01", "--to", "2023-12-31");
        assertThat(rows(result.output())).hasSize(1);
    }

    @Test
    void theSettlementRowCarriesTheNet() {
        settled();
        List<String> row = rows(run(withSpan("export-settlements")).output()).get(1);
        assertThat(new BigDecimal(row.get(Exports.SETTLEMENT_COLUMNS.indexOf("net"))))
                .isPositive();
    }

    @Test
    void theSettlementExportCanBeNarrowedToASite() {
        settled();
        assertThat(rows(run(withSpan("export-settlements", "--location", "LURAY"))
                        .output()))
                .hasSize(1);
    }

    @Test
    void theSettlementExportWritesToAFile() {
        settled();
        Path out = tmp.resolve("settlements.csv");
        Shell.Result result = run(withSpan("export-settlements", "--out", out.toString()));
        assertThat(result.output()).contains("1 settlements written");
        assertThat(out).exists();
    }

    @Test
    void theLineExportHasARowForEachPrintedLine() {
        settled();
        List<List<String>> rows = rows(run(withSpan("export-lines")).output());
        assertThat(rows.get(0)).isEqualTo(Exports.LINE_COLUMNS);
        assertThat(rows.size()).isGreaterThan(2);
    }

    @Test
    void theLinesCarryTheSettlementNumber() {
        settled();
        Set<String> numbers = rows(run(withSpan("export-lines")).output()).stream()
                .skip(1)
                .map(row -> row.get(0))
                .collect(Collectors.toSet());
        assertThat(numbers).containsExactly("S-2024-0001");
    }

    @Test
    void everyLineHasAKind() {
        settled();
        int kind = Exports.LINE_COLUMNS.indexOf("kind");
        assertThat(rows(run(withSpan("export-lines")).output()).stream().skip(1))
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.get(kind)).isNotEmpty());
    }

    @Test
    void theGrossLineIsSignedPositive() {
        settled();
        int signed = Exports.LINE_COLUMNS.indexOf("signed");
        int kind = Exports.LINE_COLUMNS.indexOf("kind");
        List<String> gross = rows(run(withSpan("export-lines")).output()).stream()
                .skip(1)
                .filter(row -> row.get(kind).equals("gross"))
                .findFirst()
                .orElseThrow();
        assertThat(new BigDecimal(gross.get(signed))).isPositive();
    }

    @Test
    void aDeductionLineIsSignedNegative() {
        settled();
        int signed = Exports.LINE_COLUMNS.indexOf("signed");
        int kind = Exports.LINE_COLUMNS.indexOf("kind");
        List<List<String>> charges = rows(run(withSpan("export-lines")).output()).stream()
                .skip(1)
                .filter(row -> !row.get(kind).equals("gross"))
                .toList();
        assertThat(charges).isNotEmpty().allSatisfy(row -> assertThat(new BigDecimal(row.get(signed)))
                .isNotPositive());
    }

    @Test
    void theLineExportWritesToAFile() {
        settled();
        Path out = tmp.resolve("lines.csv");
        Shell.Result result = run(withSpan("export-lines", "--out", out.toString()));
        assertThat(result.output()).contains("lines written to");
        assertThat(out).exists();
    }

    @Test
    void thePatronageExportKeepsALeadingZero() {
        weigh("0041", 2);
        settle("0041");
        assertThat(rows(run("patronage-export", "2024").output()).get(1).get(0)).isEqualTo("=\"0041\"");
    }

    @Test
    void aCodeThatDoesNotStartWithAZeroIsLeftAlone() {
        settled();
        assertThat(rows(run("patronage-export", "2024").output()).get(1).get(0)).isEqualTo("MERRICK");
    }
}
