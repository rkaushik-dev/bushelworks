package io.github.rkaushikdev.bushelworks.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.bushelworks.HouseFixtures;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.TestDatabase;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.services.houses.Houses;
import io.github.rkaushikdev.bushelworks.services.parties.Parties;
import io.github.rkaushikdev.bushelworks.services.scalehouse.ScaleHouse;
import io.github.rkaushikdev.bushelworks.services.settlement.SettlementRun;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The commands the office runs in September and the manager runs at eight. */
class CliBoardTest {

    static final LocalDate SETTLED = LocalDate.of(2024, 10, 20);
    static final BigDecimal SPOT = new BigDecimal("4.3500");

    @TempDir
    Path tmp;

    Database database;
    Shell shell;

    @BeforeEach
    void freshDatabase() {
        database = TestDatabase.fresh();
        shell = Shell.on(database);
    }

    /** MERRICK with three loads and HOLLIS with two, each producer on file and settled. */
    void settled() {
        RuleBook rules = HouseFixtures.rules();
        ScaleTicket corn = HouseFixtures.cornTicket();
        producer("MERRICK", "Merrick Farms", 3, 10441, rules, corn);
        producer("HOLLIS", "Hollis Farms", 2, 20441, rules, corn);
    }

    void producer(String code, String name, int loads, int firstNumber, RuleBook rules, ScaleTicket corn) {
        try (UnitOfWork uow = UnitOfWork.open(database)) {
            Parties.openAccount(uow, code, name);
            for (int index = 0; index < loads; index++) {
                ScaleHouse.recordLoad(
                        uow,
                        corn.toBuilder()
                                .ticketNumber("A-" + (firstNumber + index))
                                .build(),
                        code,
                        rules);
            }
            SettlementRun.settleProducer(uow, code, "HOME", Houses.houseRulebook(), SETTLED, SPOT);
            uow.commit();
        }
    }

    /** A membership roll as the accounting package exports it, with its header comment. */
    String aRoll(String... codes) throws IOException {
        Path path = tmp.resolve("members.txt");
        Files.writeString(
                path,
                "# exported from the accounting package\n" + String.join("\n", codes) + "\n",
                StandardCharsets.UTF_8);
        return path.toString();
    }

    Shell.Result run(String... args) {
        return shell.run(args);
    }

    @Test
    void aQuietYearStillPrintsAPage() {
        Shell.Result result = run("patronage", "2024");
        assertThat(result.code()).isZero();
        assertThat(result.output()).contains("PATRONAGE BY BUSHELS");
    }

    @Test
    void thePageNamesTheProducers() {
        settled();
        assertThat(run("patronage", "2024").output()).contains("MERRICK", "Merrick Farms");
    }

    @Test
    void thePageFitsEightyColumns() {
        settled();
        assertThat(run("patronage", "2024").lines()).isNotEmpty().allMatch(line -> line.length() <= 80);
    }

    @Test
    void thePageCanBeRankedOnMargin() {
        settled();
        assertThat(run("patronage", "2024", "--order", "margin").output()).contains("PATRONAGE BY MARGIN");
    }

    @Test
    void anOrderNobodyKnowsIsRefused() {
        settled();
        Shell.Result result = run("patronage", "2024", "--order", "sideways");
        assertThat(result.code()).isEqualTo(Main.MISUSED);
        assertThat(result.err()).contains("'sideways' is not one of 'bushels', 'margin'.");
    }

    @Test
    void anotherSiteHasNothingOnIt() {
        settled();
        assertThat(run("patronage", "2024", "--location", "LURAY").output()).doesNotContain("MERRICK");
    }

    @Test
    void theYearBeforeHasNothingOnIt() {
        settled();
        assertThat(run("patronage", "2023").output()).doesNotContain("MERRICK");
    }

    @Test
    void aRollMarksTheOutsiders() throws IOException {
        settled();
        assertThat(run("patronage", "2024", "--members", aRoll("MERRICK")).output())
                .contains("HOLLIS    *");
    }

    @Test
    void theCommentLineInARollIsIgnored() throws IOException {
        settled();
        Shell.Result result = run("patronage", "2024", "--members", aRoll("MERRICK", "HOLLIS"));
        List<String> rows =
                result.lines().stream().filter(line -> line.contains("Farms")).toList();
        assertThat(rows).isNotEmpty().noneMatch(line -> line.contains("*"));
    }

    @Test
    void aRollThatIsNotThereIsRefused() {
        settled();
        Shell.Result result =
                run("patronage", "2024", "--members", tmp.resolve("nowhere.txt").toString());
        assertThat(result.code()).isEqualTo(Main.MISUSED);
        assertThat(result.err()).contains("does not exist");
    }

    @Test
    void theExportIsCsvOnStandardOutput() {
        settled();
        assertThat(run("patronage-export", "2024").out().lines().findFirst())
                .hasValueSatisfying(line -> assertThat(line).startsWith("party_code,"));
    }

    @Test
    void theExportHasARowForEachProducer() {
        settled();
        assertThat(run("patronage-export", "2024").output().strip().lines()).hasSize(3);
    }

    @Test
    void theExportCanBeWrittenToAFile() throws IOException {
        settled();
        Path out = tmp.resolve("patronage.csv");
        Shell.Result result = run("patronage-export", "2024", "--out", out.toString());
        assertThat(result.output()).contains("2 producers written");
        assertThat(Files.readString(out, StandardCharsets.UTF_8)).startsWith("party_code,");
    }

    @Test
    void theWrittenFileHasNoCarriageReturns() throws IOException {
        settled();
        Path out = tmp.resolve("patronage.csv");
        run("patronage-export", "2024", "--out", out.toString());
        assertThat(Files.readAllBytes(out)).isNotEmpty().doesNotContain((byte) '\r');
    }

    @Test
    void anAllocationNamesEveryMember() {
        settled();
        assertThat(run("allocate", "2024", "--pool", "10000.00").output()).contains("MERRICK", "HOLLIS");
    }

    @Test
    void anAllocationShowsWhatIsLeftUnallocated() {
        settled();
        assertThat(run("allocate", "2024", "--pool", "10000.00").output()).contains("UNALLOCATED");
    }

    @Test
    void theWholePoolGoesOutWhenNothingIsTooSmall() {
        settled();
        String line = run("allocate", "2024", "--pool", "10000.00").lines().stream()
                .filter(row -> row.startsWith("UNALLOCATED"))
                .findFirst()
                .orElseThrow();
        String[] words = line.split("\\s+");
        assertThat(words[words.length - 1]).isEqualTo("0.00");
    }

    @Test
    void aShareUnderTheMinimumStaysInThePool() {
        settled();
        assertThat(run("allocate", "2024", "--pool", "10.00", "--minimum", "9.00")
                        .output())
                .contains("UNALLOCATED")
                .doesNotContain("HOLLIS");
    }

    @Test
    void aNonMemberGetsNothing() throws IOException {
        settled();
        assertThat(run("allocate", "2024", "--pool", "10000.00", "--members", aRoll("MERRICK"))
                        .output())
                .contains("MERRICK")
                .doesNotContain("HOLLIS");
    }

    @Test
    void aPoolThatIsNotANumberIsRefused() {
        settled();
        assertThat(run("allocate", "2024", "--pool", "lots").code()).isEqualTo(Main.MISUSED);
    }

    @Test
    void theTopListIsBiggestFirst() {
        settled();
        List<String> lines = run("top", "2024").lines();
        assertThat(lines.get(0)).contains("MERRICK");
        assertThat(lines.get(1)).contains("HOLLIS");
    }

    @Test
    void theTopListCanBeCutShort() {
        settled();
        assertThat(run("top", "2024", "--count", "1").output())
                .doesNotContain("HOLLIS")
                .contains("these 1 hold");
    }

    @Test
    void anEmptyYearNamesNobody() {
        Shell.Result result = run("top", "2024");
        assertThat(result.code()).isZero();
        assertThat(result.output().strip()).isEmpty();
    }

    @Test
    void theBasisIsCashLessFutures() {
        assertThat(Shell.bare().run("basis", "4.1000", "4.3225").output().strip())
                .isEqualTo("-0.2225");
    }

    @Test
    void aBasisThatIsNotANumberIsRefused() {
        assertThat(Shell.bare().run("basis", "high", "4.32").code()).isEqualTo(Main.MISUSED);
    }

    @Test
    void aCarryThatBeatsStorageSaysStore() {
        Shell.Result result = Shell.bare().run("carry", "4.20", "4.45", "--months", "3", "--storage", "0.05");
        assertThat(result.output().strip()).endsWith("store");
        assertThat(result.output()).contains("0.1000 per bushel");
    }

    @Test
    void aCarryThatDoesNotCoverStorageSaysSell() {
        Shell.Result result = Shell.bare().run("carry", "4.20", "4.25", "--months", "3", "--storage", "0.05");
        assertThat(result.output().strip()).endsWith("sell");
    }

    @Test
    void aCarryOverNoMonthsIsRefused() {
        Shell.Result result = Shell.bare().run("carry", "4.20", "4.45", "--months", "0", "--storage", "0.05");
        assertThat(result.code()).isEqualTo(Main.REFUSED);
        assertThat(result.output()).contains("hedge_error");
    }
}
