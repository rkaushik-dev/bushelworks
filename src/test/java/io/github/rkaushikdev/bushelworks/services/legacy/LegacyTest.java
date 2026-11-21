package io.github.rkaushikdev.bushelworks.services.legacy;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.services.legacy.LegacyReader.RECORD_LENGTH;
import static io.github.rkaushikdev.bushelworks.services.legacy.LegacyReader.duplicates;
import static io.github.rkaushikdev.bushelworks.services.legacy.LegacyReader.parseFile;
import static io.github.rkaushikdev.bushelworks.services.legacy.LegacyReader.parseLine;
import static io.github.rkaushikdev.bushelworks.services.legacy.LegacyReader.summary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.domain.tickets.Disposition;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LegacyTest {

    static final String PACKAGE = "io.github.rkaushikdev.bushelworks.services.legacy.";

    /** The reading side: every class that parses the old file and the values it hands back. */
    static final List<Class<?>> READER = List.of(
            LegacyReader.class,
            LegacyReader.Span.class,
            LegacyRow.class,
            ParsedFile.class,
            Rejected.class,
            LegacyImportError.class);

    static String aLine(String... overrides) {
        return OldLine.text("20221014", overrides);
    }

    static String lines(String... lines) {
        return String.join("\n", lines);
    }

    @Test
    void theLayoutAddsUpToTheRecordLength() {
        assertThat(aLine()).hasSize(RECORD_LENGTH);
    }

    @Test
    void aCleanLineReads() {
        LegacyRow row = parseLine(aLine(), 1);
        assertThat(row.ticketNumber()).isEqualTo("A-10441");
        assertThat(row.scaleDate()).isEqualTo(LocalDate.of(2022, 10, 14));
        assertThat(row.partyCode()).isEqualTo("MRK001");
        assertThat(row.grossPounds()).isEqualByComparingTo("78420");
        assertThat(row.netPounds()).isEqualByComparingTo("52240");
        assertThat(row.disposition()).isEqualTo(Disposition.CASH_SALE);
        assertThat(row.disposition().value()).isEqualTo("cash_sale");
        assertThat(row.voided()).isFalse();
    }

    @Test
    void theFactorsComeAcross() {
        LegacyRow row = parseLine(aLine(), 1);
        assertThat(row.moisture()).contains(d("18.2"));
        assertThat(row.testWeight()).contains(d("56.5"));
        assertThat(row.damage()).contains(d("1.0"));
    }

    @Test
    void aBlankFactorReadsAsNotTestedRatherThanZero() {
        assertThat(parseLine(aLine("moisture", "      "), 1).moisture()).isEmpty();
    }

    @Test
    void aFactorThatIsNotANumberReadsAsNotTested() {
        assertThat(parseLine(aLine("damage", "  N/A "), 1).damage()).isEmpty();
    }

    @Test
    void aWeightWithACommaStillReads() {
        assertThat(parseLine(aLine("gross", "    78,420"), 1).grossPounds()).isEqualByComparingTo("78420");
    }

    @Test
    void aVoidFlagIsRead() {
        assertThat(parseLine(aLine("void", "V"), 1).voided()).isTrue();
    }

    @Test
    void thePartyCodeCanBeMapped() {
        assertThat(parseLine(aLine(), 1, Map.of("MRK001", "MERRICK")).partyCode())
                .isEqualTo("MERRICK");
    }

    @Test
    void anUnmappedPartyCodeComesAcrossAsItIs() {
        assertThat(parseLine(aLine(), 1, Map.of("OTH999", "OTHER")).partyCode()).isEqualTo("MRK001");
    }

    @Test
    void aShortLineIsRefused() {
        assertThatThrownBy(() -> parseLine(aLine().substring(0, 40), 1)).isInstanceOf(LegacyImportError.class);
    }

    @Test
    void aLineWithNoTicketNumberIsRefused() {
        assertThatThrownBy(() -> parseLine(aLine("ticket", "          "), 1)).isInstanceOf(LegacyImportError.class);
    }

    @Test
    void aLineWithNoPartyIsRefused() {
        assertThatThrownBy(() -> parseLine(aLine("party", "      "), 1)).isInstanceOf(LegacyImportError.class);
    }

    @Test
    void aBadDateIsRefusedNamingIt() {
        assertThatThrownBy(() -> parseLine(aLine("scale_date", "20221345"), 1))
                .isInstanceOf(LegacyImportError.class)
                .hasMessageContaining("20221345");
    }

    /**
     * The date field is eight columns, and a short date in it is read the first way that fits from
     * the left, a two digit month before a one digit one. The reading has to use the whole field and
     * name a day the calendar has.
     */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "'20221014'|2022-10-14",
                "'20221231'|2022-12-31",
                "'2022111 '|2022-11-01",
                "'2022123 '|2022-12-03",
                "'202211  '|2022-01-01",
                "'20221 4 '|2022-01-04",
                "'2022010 '|",
                "'20221345'|",
                "'20220230'|",
                "'00001014'|",
                "'2022 101'|",
            })
    void aShortDateIsReadFromTheLeft(String field, String expected) {
        String line = aLine("scale_date", field);
        if (expected == null) {
            assertThatThrownBy(() -> parseLine(line, 1))
                    .isInstanceOf(LegacyImportError.class)
                    .hasMessageContaining("is not a date");
        } else {
            assertThat(parseLine(line, 1).scaleDate()).isEqualTo(LocalDate.parse(expected));
        }
    }

    @Test
    void aBlankWeightIsRefused() {
        assertThatThrownBy(() -> parseLine(aLine("gross", "          "), 1)).isInstanceOf(LegacyImportError.class);
    }

    @Test
    void aWeightThatIsNotANumberIsRefused() {
        assertThatThrownBy(() -> parseLine(aLine("tare", "     ABCDE"), 1)).isInstanceOf(LegacyImportError.class);
    }

    @Test
    void aTareAboveGrossIsRefused() {
        assertThatThrownBy(() -> parseLine(aLine("gross", "     26180", "tare", "     78420"), 1))
                .isInstanceOf(LegacyImportError.class);
    }

    @Test
    void anUnknownDispositionIsRefused() {
        assertThatThrownBy(() -> parseLine(aLine("disposition", "BORROWED"), 1)).isInstanceOf(LegacyImportError.class);
    }

    @Test
    void aWholeFileReads() {
        ParsedFile result = parseFile(lines(aLine(), aLine("ticket", "A-10442   ")));
        assertThat(result.read()).isEqualTo(2);
        assertThat(result.isClean()).isTrue();
    }

    @Test
    void blankLinesAreSkipped() {
        assertThat(parseFile(aLine() + "\n\n" + aLine("ticket", "A-10442   ")).read())
                .isEqualTo(2);
    }

    @Test
    void aBadRowIsKeptWithItsReason() {
        ParsedFile result = parseFile(lines(aLine(), aLine("disposition", "BORROWED")));
        assertThat(result.read()).isEqualTo(1);
        assertThat(result.refused()).isEqualTo(1);
        assertThat(result.rejected().get(0).reason()).contains("disposition");
        assertThat(result.rejected().get(0).lineNumber()).isEqualTo(2);
    }

    @Test
    void aBadRowDoesNotCostTheGoodOnes() {
        ParsedFile result = parseFile(lines(aLine(), aLine("scale_date", "ZZZZZZZZ"), aLine("ticket", "A-10443   ")));
        assertThat(result.rows()).extracting(LegacyRow::ticketNumber).containsExactly("A-10441", "A-10443");
    }

    @Test
    void rowsCanBePulledOutByProducer() {
        ParsedFile result = parseFile(lines(aLine(), aLine("ticket", "A-10442   ", "party", "HOL001")));
        assertThat(result.byParty("MRK001")).hasSize(1);
    }

    @Test
    void aRepeatedTicketNumberAtOneSiteIsReported() {
        assertThat(duplicates(parseFile(lines(aLine(), aLine("gross", "     78500")))))
                .containsExactly("HOME/A-10441");
    }

    @Test
    void theSameNumberAtTwoSitesIsNotADuplicate() {
        assertThat(duplicates(parseFile(lines(aLine(), aLine("location", "LURA")))))
                .isEmpty();
    }

    @Test
    void theSummaryOfACleanFile() {
        assertThat(summary(parseFile(aLine()))).isEqualTo("1 tickets read, none refused");
    }

    @Test
    void theSummaryNamesWhatWentWrong() {
        String text = summary(parseFile(lines(aLine(), aLine("disposition", "BORROWED"))));
        assertThat(text).contains("1 refused").contains("disposition");
    }

    /**
     * The reader and the writer are two classes and the split is meant to be invisible: everything
     * the migration and its command use is still in this one package, under the names they use.
     */
    @Test
    void thePackageStillExportsEverythingItUsedTo() {
        List<String> exported = List.of(
                "LegacyReader.DISPOSITIONS",
                "LegacyReader.LAYOUT",
                "LegacyReader.RECORD_LENGTH",
                "LegacyImportError",
                "Loaded",
                "Rejected",
                "ParsedFile",
                "LegacyRow",
                "LegacyReader.duplicates",
                "LegacyReader.fieldAt",
                "LegacyLoader.load",
                "LegacyLoader.loadSummary",
                "LegacyReader.parseFile",
                "LegacyReader.parseLine",
                "LegacyReader.summary",
                "LegacyLoader.toTicket");
        for (String name : exported) {
            assertThat(isExported(name)).as(name).isTrue();
        }
        for (String planted : List.of("LegacyReader.load", "LegacyLoader.parseFile", "Result", "LegacyReader.lines")) {
            assertThat(isExported(planted))
                    .as(planted + " is not part of the package")
                    .isFalse();
        }
    }

    /**
     * Reading has no business knowing there is a database. This is the wall the split was for: a
     * reader that reaches for the unit of work will eventually write through it, and then a dry run is
     * not a dry run.
     */
    @Test
    void theReaderDoesNotReachForTheDatabase() throws IOException {
        for (Class<?> type : READER) {
            String references = references(type);
            assertThat(references).as(type.getName()).doesNotContain("persistence/UnitOfWork");
            assertThat(references).as(type.getName()).doesNotContain("recordLoad");
        }
        String writer = references(LegacyLoader.class);
        assertThat(writer).as("the writer does reach for both").contains("persistence/UnitOfWork", "recordLoad");
    }

    @Test
    void theWriterLeansOnTheReaderAndNotTheOtherWay() throws IOException {
        assertThat(references(LegacyLoader.class)).contains("services/legacy/ParsedFile", "services/legacy/LegacyRow");
        for (Class<?> type : READER) {
            assertThat(references(type)).as(type.getName()).doesNotContain("LegacyLoader", "services/legacy/Loaded");
        }
    }

    /** Whether {@code name}, a type or a type and a public static member, is public in the package. */
    static boolean isExported(String name) {
        String[] parts = name.split("\\.", 2);
        Class<?> type;
        try {
            type = Class.forName(PACKAGE + parts[0]);
        } catch (ClassNotFoundException missing) {
            return false;
        }
        if (!Modifier.isPublic(type.getModifiers())) {
            return false;
        }
        if (parts.length == 1) {
            return true;
        }
        String member = parts[1];
        boolean field = Arrays.stream(type.getFields())
                .filter(each -> Modifier.isStatic(each.getModifiers()))
                .map(Field::getName)
                .anyMatch(member::equals);
        boolean method = Arrays.stream(type.getMethods())
                .filter(each -> Modifier.isStatic(each.getModifiers()))
                .map(Method::getName)
                .anyMatch(member::equals);
        return field || method;
    }

    /** The names a compiled class refers to: its constant pool, as text. */
    static String references(Class<?> type) throws IOException {
        String file = type.getName().substring(type.getPackageName().length() + 1) + ".class";
        try (InputStream in = type.getResourceAsStream(file)) {
            assertThat(in).as(file).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
