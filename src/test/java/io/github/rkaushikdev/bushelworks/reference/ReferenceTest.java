package io.github.rkaushikdev.bushelworks.reference;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.bushelworks.cli.Main;
import io.github.rkaushikdev.bushelworks.cli.Shell;
import io.github.rkaushikdev.bushelworks.domain.commodity.Commodities;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The generated reference has to match what the code actually does, and the README's copy has to
 * match the reference.
 */
class ReferenceTest {

    /** The README at the root of the project, which the build runs the tests from. */
    static final Path README = Path.of("README.md");

    /** A command in the group's own help: two spaces in, then its name. */
    private static final Pattern LISTED = Pattern.compile("^ {2}(\\S+)");

    static String readme() throws IOException {
        return Files.readString(README, StandardCharsets.UTF_8);
    }

    /** The commands the command line mounts, read off the list its {@code --help} prints. */
    static Set<String> mountedCommands() {
        Shell.Result help = Shell.bare().run("--help");
        assertThat(help.code()).isZero();
        List<String> lines = help.out().lines().toList();
        int heading = lines.indexOf("Commands:");
        assertThat(heading).as("the help has a list of commands").isNotNegative();
        Set<String> names = new LinkedHashSet<>();
        for (String line : lines.subList(heading + 1, lines.size())) {
            Matcher command = LISTED.matcher(line);
            if (command.find()) {
                names.add(command.group(1));
            }
        }
        return names;
    }

    static List<String> rungRows(String table) {
        return table.lines().filter(line -> line.startsWith("| US")).toList();
    }

    @Test
    void everyCommandIsListed() {
        Set<String> listed =
                Reference.commandRows().stream().map(Reference.CommandRow::name).collect(Collectors.toSet());
        Set<String> mounted = mountedCommands();
        assertThat(mounted).as("the help listed commands").contains("settle", "serve");
        assertThat(listed).isEqualTo(mounted).isEqualTo(Main.commandSummaries().keySet());
    }

    @Test
    void everyCommandHasALineOfHelp() {
        for (Reference.CommandRow row : Reference.commandRows()) {
            assertThat(row.help()).as(row.name()).isNotBlank();
        }
    }

    @Test
    void theCommandTableIsMarkdown() {
        String text = Reference.commandReference();
        assertThat(text.split("\n", -1)[0]).startsWith("| command");
        assertThat(text).contains("settle", "position");
    }

    @Test
    void theCommodityTableListsTheWholeMaster() {
        String text = Reference.commodityReference();
        assertThat(Commodities.codes()).isNotEmpty();
        for (String code : Commodities.codes()) {
            assertThat(text).contains(code);
        }
    }

    @Test
    void theCommodityTableCarriesTheTestWeight() {
        assertThat(Reference.commodityReference()).contains("| 56 |");
    }

    @Test
    void theGradeLadderHasARowPerRung() {
        assertThat(rungRows(Reference.gradeReference("CORN"))).hasSize(5);
    }

    @Test
    void theGradeLadderNamesItsFactors() {
        String text = Reference.gradeReference("CORN");
        assertThat(text).contains("test weight", "bcfm");
    }

    @Test
    void aLadderWithFewerGradesHasFewerRows() {
        assertThat(rungRows(Reference.gradeReference("SOYB"))).hasSize(4);
    }

    @Test
    void theDiscountTableListsTheFiledSchedules() {
        String text = Reference.discountReference("CORN");
        assertThat(text).contains("test weight", "bcfm");
    }

    @Test
    void aCommodityWithNoSchedulesSaysSo() {
        assertThat(Reference.discountReference("MILO")).isEqualTo("no discount schedules filed");
    }

    @Test
    void theWorkedExampleIsTheLoadEverythingUses() {
        String text = Reference.workedExample();
        assertThat(text).contains("78420 lb", "932.8571", "897.6324");
    }

    /** Each line's figure is in the README, and so is the whole line it stands on. */
    @Test
    void theReadmeCarriesTheSameWorkedLoad() throws IOException {
        String readme = readme();
        for (String line : Reference.workedExample().split("\n", -1)) {
            String[] words = line.strip().split("\\s+");
            String figure = words[words.length - 1];
            assertThat(readme).as(figure).contains(figure);
            assertThat(readme).as(line).contains(line);
        }
    }

    @Test
    void renderingEverythingHoldsEachSection() {
        String text = Reference.renderAll();
        for (String heading : List.of(
                "## Commands",
                "## Commodities",
                "## Corn grade ladder",
                "## Corn discount schedules",
                "## A worked load")) {
            assertThat(text).contains(heading);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CORN", "SOYB", "HRW", "MILO"})
    void everyLadderRenders(String commodityCode) {
        assertThat(Reference.gradeReference(commodityCode)).startsWith("| grade |");
    }

    /** What the printer prints for each section, and for the commodity it is told. */
    static Stream<Arguments> sections() {
        return Stream.of(
                Arguments.of(List.of(), (Supplier<String>) Reference::renderAll),
                Arguments.of(List.of("--section", "all"), (Supplier<String>) Reference::renderAll),
                Arguments.of(List.of("--section", "commands"), (Supplier<String>) Reference::commandReference),
                Arguments.of(List.of("--section", "commodities"), (Supplier<String>) Reference::commodityReference),
                Arguments.of(List.of("--section", "grades"), (Supplier<String>) () -> Reference.gradeReference("CORN")),
                Arguments.of(List.of("--section", "grades", "--commodity", "SOYB"), (Supplier<String>)
                        () -> Reference.gradeReference("SOYB")),
                Arguments.of(List.of("--section", "discounts", "--commodity", "MILO"), (Supplier<String>)
                        () -> Reference.discountReference("MILO")),
                Arguments.of(List.of("--section", "load"), (Supplier<String>) Reference::workedExample));
    }

    @ParameterizedTest
    @MethodSource("sections")
    void thePrinterPrintsTheSectionAskedFor(List<String> args, Supplier<String> expected) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = Reference.run(new PrintWriter(out), new PrintWriter(err), args.toArray(String[]::new));
        assertThat(code).isZero();
        assertThat(out.toString()).isEqualTo(expected.get() + System.lineSeparator());
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void thePrinterRefusesASectionItDoesNotHave() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int code = Reference.run(new PrintWriter(out), new PrintWriter(err), "--section", "plough");
        assertThat(code).isEqualTo(2);
        assertThat(out.toString()).isEmpty();
        assertThat(err.toString())
                .contains("'plough' is not one of 'all', 'commands', 'commodities', 'grades', 'discounts', 'load'.");
    }

    @Test
    void thePrinterSaysWhatItTakes() {
        StringWriter out = new StringWriter();
        int code = Reference.run(new PrintWriter(out), new PrintWriter(new StringWriter()), "--help");
        assertThat(code).isZero();
        assertThat(out.toString()).contains("--section", "--commodity", "Print reference material");
    }
}
