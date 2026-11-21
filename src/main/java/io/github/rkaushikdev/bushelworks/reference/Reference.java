package io.github.rkaushikdev.bushelworks.reference;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Quoting;
import io.github.rkaushikdev.bushelworks.Units;
import io.github.rkaushikdev.bushelworks.ValueEnum;
import io.github.rkaushikdev.bushelworks.cli.Main;
import io.github.rkaushikdev.bushelworks.domain.commodity.Commodities;
import io.github.rkaushikdev.bushelworks.domain.commodity.Commodity;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.domain.discounts.DiscountBand;
import io.github.rkaushikdev.bushelworks.domain.discounts.DiscountBook;
import io.github.rkaushikdev.bushelworks.domain.grade.GradeRung;
import io.github.rkaushikdev.bushelworks.domain.grade.Grading;
import io.github.rkaushikdev.bushelworks.domain.moisture.ShrinkSchedule;
import io.github.rkaushikdev.bushelworks.reports.Columns;
import io.github.rkaushikdev.bushelworks.services.houses.Houses;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.Option;
import picocli.CommandLine.TypeConversionException;

/**
 * Reference material generated from the code: the command list, the commodity master, the grade
 * ladders, the discount schedules and the worked load.
 *
 * <p>Each of these used to be written out by hand, in the README and on the office's wall sheet, and
 * the hand written copies went stale. They are generated here instead, so the README and the wall
 * sheet are always what the software actually does. The suite compares the README's worked load
 * with {@link #workedExample()}, so a change to the arithmetic that the README does not follow fails
 * there.
 *
 * <p>Every table is markdown. {@link #main} prints one section, or all of them in the order the
 * README carries them, to paste in.
 */
public final class Reference {

    /** What the discount table says for a commodity with nothing filed. */
    public static final String NO_SCHEDULES = "no discount schedules filed";

    /** The worked load's commodity. */
    private static final String CORN = "CORN";

    private static final int LABEL_WIDTH = 15;

    private Reference() {}

    /**
     * One command and the first line of what it says it does.
     *
     * @param name what the command is typed as
     * @param help the first line of its help
     */
    public record CommandRow(String name, String help) {}

    /** Every command on the command line with the first line of its help, in name order. */
    public static List<CommandRow> commandRows() {
        return Main.commandSummaries().entrySet().stream()
                .map(command -> new CommandRow(command.getKey(), command.getValue()))
                .toList();
    }

    /** The command list, as a markdown table. */
    public static String commandReference() {
        List<CommandRow> rows = commandRows();
        int width = rows.stream().mapToInt(row -> row.name().length()).max().orElseThrow();
        List<String> lines = new ArrayList<>();
        lines.add("| " + Columns.left("command", width) + " | what it does |");
        lines.add("| " + "-".repeat(width) + " | --- |");
        rows.forEach(row -> lines.add("| " + Columns.left(row.name(), width) + " | " + row.help() + " |"));
        return String.join("\n", lines);
    }

    /** The commodity master, as the office pins it to the wall. */
    public static String commodityReference() {
        List<String> lines = new ArrayList<>();
        lines.add("| code | commodity | lb/bu | base moisture | priced per |");
        lines.add("| --- | --- | --- | --- | --- |");
        for (String code : Commodities.codes()) {
            Commodity one = Commodities.get(code);
            lines.add("| " + one.code() + " | " + one.name() + " | " + one.standardTestWeight() + " | "
                    + one.baseMoisture() + "% | " + one.priceUnit().value() + " |");
        }
        return String.join("\n", lines);
    }

    /**
     * One commodity's grade ladder, best rung first, which is the order it is walked. There is a
     * column for every factor any rung looks at, in the order of their values; a rung that does not
     * look at a factor leaves its cell empty.
     */
    public static String gradeReference(String commodityCode) {
        List<GradeRung> rungs = Grading.ladder(commodityCode);
        List<Factor> factors = Grading.gradedFactors(commodityCode).stream()
                .sorted(Factor.BY_VALUE)
                .toList();
        List<String> lines = new ArrayList<>();
        lines.add("| grade | " + cells(factors.stream().map(Reference::spoken).toList()));
        lines.add("| --- | " + cells(factors.stream().map(factor -> "---").toList()));
        for (GradeRung rung : rungs) {
            List<String> limits = factors.stream()
                    .map(factor -> rung.limits().containsKey(factor)
                            ? rung.limits().get(factor).toString()
                            : "")
                    .toList();
            lines.add("| US " + rung.grade().value() + " | " + cells(limits));
        }
        return String.join("\n", lines);
    }

    /**
     * What the house discounts a commodity for, and where each schedule's first band starts, or
     * {@link #NO_SCHEDULES} when the house files none for it.
     */
    public static String discountReference(String commodityCode) {
        DiscountBook book = Houses.discountBook();
        List<Factor> factors = book.factors(commodityCode);
        if (factors.isEmpty()) {
            return NO_SCHEDULES;
        }
        List<String> lines = new ArrayList<>();
        lines.add("| factor | starts at | rate | per | mode |");
        lines.add("| --- | --- | --- | --- | --- |");
        for (Factor factor : factors) {
            book.forFactor(commodityCode, factor).ifPresent(schedule -> {
                DiscountBand band = schedule.bands().get(0);
                lines.add("| " + spoken(factor) + " | " + band.start() + " | " + band.rate() + " | " + band.increment()
                        + " | " + band.mode().value() + " |");
            });
        }
        return String.join("\n", lines);
    }

    /**
     * The load every test and every explanation uses: a truck of corn at 18.2 percent moisture,
     * weighed, turned into bushels at the standard test weight and shrunk on the table at 1.18 a
     * point over a 15.0 base.
     */
    public static String workedExample() {
        ShrinkSchedule schedule = ShrinkSchedule.of(CORN, new BigDecimal("15.0"));
        BigDecimal testWeight = Commodities.get(CORN).standardTestWeight();
        BigDecimal gross = new BigDecimal("78420");
        BigDecimal tare = new BigDecimal("26180");
        BigDecimal net = gross.subtract(tare);
        BigDecimal bushels =
                Decimals.quantize(Decimals.divide(net, testWeight), Units.BUSHEL_PLACES, RoundingMode.HALF_EVEN);
        BigDecimal moisture = new BigDecimal("18.2");
        BigDecimal dry = schedule.dryBushels(bushels, moisture);
        return String.join(
                "\n",
                labelled("gross", gross + " lb"),
                labelled("tare", tare + " lb"),
                labelled("net", net + " lb"),
                labelled("bushels", bushels + " at " + testWeight + " lb/bu"),
                labelled("moisture", moisture + "% against a " + schedule.baseMoisture() + "% base"),
                labelled("shrink", schedule.shrinkPercent(moisture) + "%"),
                labelled("dry bushels", dry.toString()));
    }

    /** Everything, in the order the README carries it, each section under its own heading. */
    public static String renderAll() {
        return String.join(
                "\n\n",
                "## Commands",
                commandReference(),
                "## Commodities",
                commodityReference(),
                "## Corn grade ladder",
                gradeReference(CORN),
                "## Corn discount schedules",
                discountReference(CORN),
                "## A worked load",
                "```\n" + workedExample() + "\n```");
    }

    /** Print the section the arguments ask for and exit with the code {@link #run} hands back. */
    public static void main(String... args) {
        Charset charset = Charset.defaultCharset();
        System.exit(run(new PrintWriter(System.out, true, charset), new PrintWriter(System.err, true, charset), args));
    }

    /**
     * Print the section {@code args} ask for to {@code out}.
     *
     * @return 0 when it printed, 2 when the arguments could not be read, the complaint having gone to
     *     {@code err}
     */
    public static int run(PrintWriter out, PrintWriter err, String... args) {
        try {
            return new CommandLine(new Print(out))
                    .setExpandAtFiles(false)
                    .setOverwrittenOptionsAllowed(true)
                    .setOut(out)
                    .setErr(err)
                    .execute(args);
        } finally {
            out.flush();
            err.flush();
        }
    }

    private static String spoken(Factor factor) {
        return factor.value().replace('_', ' ');
    }

    /** The cells of a table row after its first, each closed by a bar. */
    private static String cells(List<String> cells) {
        return String.join(" | ", cells) + " |";
    }

    private static String labelled(String label, String figure) {
        return Columns.left(label, LABEL_WIDTH) + figure;
    }

    /** The sections {@link #main} can print, each written as the word it is asked for by. */
    enum Section implements ValueEnum {
        ALL("all"),
        COMMANDS("commands"),
        COMMODITIES("commodities"),
        GRADES("grades"),
        DISCOUNTS("discounts"),
        LOAD("load");

        private final String value;

        Section(String value) {
            this.value = value;
        }

        @Override
        public String value() {
            return value;
        }
    }

    /** A section named by its word, spelled exactly. */
    static final class SectionName implements ITypeConverter<Section> {

        @Override
        public Section convert(String value) {
            if (ValueEnum.isValue(Section.class, value)) {
                return ValueEnum.of(Section.class, value);
            }
            String named = Arrays.stream(Section.values())
                    .map(section -> Quoting.quoted(section.value()))
                    .collect(Collectors.joining(", "));
            throw new TypeConversionException(Quoting.quoted(value) + " is not one of " + named + ".");
        }
    }

    /** The printer {@link #run} drives. */
    @Command(name = "reference", description = "Print reference material generated from the code.")
    static final class Print implements Callable<Integer> {

        private final PrintWriter out;

        @Option(names = "--help", usageHelp = true, description = "Show this message and exit.")
        private boolean helpAsked;

        @Option(
                names = "--section",
                defaultValue = "all",
                converter = SectionName.class,
                paramLabel = "[all|commands|commodities|grades|discounts|load]",
                description = "Which part to print. All of it by default.")
        private Section section;

        @Option(
                names = "--commodity",
                defaultValue = CORN,
                paramLabel = "TEXT",
                description = "The commodity the grade and discount tables are for.")
        private String commodity;

        Print(PrintWriter out) {
            this.out = out;
        }

        @Override
        public Integer call() {
            out.println(
                    switch (section) {
                        case COMMANDS -> commandReference();
                        case COMMODITIES -> commodityReference();
                        case GRADES -> gradeReference(commodity);
                        case DISCOUNTS -> discountReference(commodity);
                        case LOAD -> workedExample();
                        case ALL -> renderAll();
                    });
            return 0;
        }
    }
}
