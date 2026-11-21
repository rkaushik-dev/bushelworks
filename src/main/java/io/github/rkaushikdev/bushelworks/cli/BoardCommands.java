package io.github.rkaushikdev.bushelworks.cli;

import static io.github.rkaushikdev.bushelworks.reports.Columns.left;
import static io.github.rkaushikdev.bushelworks.reports.Columns.right;

import io.github.rkaushikdev.bushelworks.Text;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.reports.patronage.PatronageBoard;
import io.github.rkaushikdev.bushelworks.reports.patronage.PatronageYear;
import io.github.rkaushikdev.bushelworks.services.desk.Desk;
import io.github.rkaushikdev.bushelworks.services.patronage.Patronage;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 * The board and desk commands.
 *
 * <p>The office runs these once a year and the manager runs the desk ones every morning, so they have
 * opposite shapes. The patronage commands take a crop year and write a file; the desk commands take a
 * handful of numbers off the screen and print an answer.
 *
 * <p>The membership roll comes in as a file of codes, one per line, because it is kept in the
 * accounting package and exported from there. Typing four hundred codes onto a command line is not a
 * workflow anybody has.
 */
final class BoardCommands {

    /** The commands this module adds to the group. */
    static final List<Class<?>> COMMANDS =
            List.of(PrintPatronage.class, ExportPatronage.class, Allocate.class, Top.class, Basis.class, Carry.class);

    private BoardCommands() {}

    /**
     * The member codes in a roll, or null for a house that is not a co-op.
     *
     * <p>Blank lines and anything after a hash are dropped, because the export from the accounting
     * package has a header comment on it and always will.
     */
    static List<String> roll(Path path) {
        if (path == null) {
            return null;
        }
        List<String> codes = new ArrayList<>();
        for (String line : Text.lines(TextFiles.read(path))) {
            String code = Text.trimmed(line.split("#", 2)[0]);
            if (!code.isEmpty()) {
                codes.add(code);
            }
        }
        return codes;
    }

    /** The patronage table, printed. */
    @Command(name = "patronage", description = "Print the patronage table for a crop year.")
    static final class PrintPatronage implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Parameters(index = "0", paramLabel = "YEAR")
        private int year;

        @Option(names = "--location", paramLabel = "TEXT", description = "Limit to one elevator.")
        private String location;

        @Option(
                names = "--members",
                paramLabel = "FILE",
                converter = Arguments.ExistingFile.class,
                description = "A file of member codes, one per line.")
        private Path members;

        @Option(
                names = "--order",
                defaultValue = PatronageBoard.BY_BUSHELS,
                paramLabel = "[bushels|margin]",
                converter = Arguments.Ranking.class,
                description = "Rank on bushels delivered or on margin kept.")
        private String order;

        @Option(
                names = "--limit",
                defaultValue = "25",
                paramLabel = "INTEGER",
                description = "How many producers to name.")
        private int limit;

        @Override
        public Integer call() {
            String page;
            try (UnitOfWork uow = main.unitOfWork()) {
                PatronageYear built = Patronage.buildCropYear(uow, year, location, roll(members));
                page = PatronageBoard.render(built, order, limit);
            }
            main.out().println(page);
            return Main.OK;
        }
    }

    /** The patronage table, for the accountant. */
    @Command(name = "patronage-export", description = "Write the patronage table as CSV for the accountant.")
    static final class ExportPatronage implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Parameters(index = "0", paramLabel = "YEAR")
        private int year;

        @Option(names = "--location", paramLabel = "TEXT", description = "Limit to one elevator.")
        private String location;

        @Option(
                names = "--members",
                paramLabel = "FILE",
                converter = Arguments.ExistingFile.class,
                description = "A file of member codes, one per line.")
        private Path members;

        @Option(
                names = "--out",
                paramLabel = "FILE",
                converter = Arguments.OutputFile.class,
                description = "Write here instead of to standard output.")
        private Path out;

        @Override
        public Integer call() {
            String text;
            try (UnitOfWork uow = main.unitOfWork()) {
                text = PatronageBoard.toCsv(Patronage.buildCropYear(uow, year, location, roll(members)));
            }
            if (out == null) {
                main.out().print(text);
                return Main.OK;
            }
            TextFiles.write(out, text);
            main.out().println((Text.lines(text).size() - 1) + " producers written to " + out);
            return Main.OK;
        }
    }

    /** A patronage pool, split. */
    @Command(name = "allocate", description = "Work out what each member would get out of a patronage pool.")
    static final class Allocate implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Parameters(index = "0", paramLabel = "YEAR")
        private int year;

        @Option(
                names = "--pool",
                required = true,
                paramLabel = "DECIMAL",
                converter = Arguments.Exact.class,
                description = "Dollars to split.")
        private BigDecimal pool;

        @Option(names = "--location", paramLabel = "TEXT", description = "Limit to one elevator.")
        private String location;

        @Option(
                names = "--members",
                paramLabel = "FILE",
                converter = Arguments.ExistingFile.class,
                description = "A file of member codes, one per line.")
        private Path members;

        @Option(
                names = "--minimum",
                defaultValue = "5.00",
                paramLabel = "DECIMAL",
                converter = Arguments.Exact.class,
                description = "Shares under this stay in the pool.")
        private BigDecimal minimum;

        @Override
        public Integer call() {
            Map<String, BigDecimal> split;
            try (UnitOfWork uow = main.unitOfWork()) {
                PatronageYear built = Patronage.buildCropYear(uow, year, location, roll(members));
                split = Patronage.allocate(built, pool, minimum);
            }
            BigDecimal paid = split.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            PrintWriter out = main.out();
            Comparator<String> biggestFirst = Comparator.comparing(split::get, Comparator.reverseOrder());
            split.keySet().stream()
                    .sorted(biggestFirst.thenComparing(Comparator.naturalOrder()))
                    .forEach(code ->
                            out.println(left(code, 24) + right(split.get(code).toString(), 14)));
            out.println(left("ALLOCATED", 24) + right(paid.toString(), 14));
            out.println(left("UNALLOCATED", 24) + right(pool.subtract(paid).toString(), 14));
            return Main.OK;
        }
    }

    /** The biggest producers of a crop year. */
    @Command(name = "top", description = "Name the biggest producers of a crop year, biggest first.")
    static final class Top implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Parameters(index = "0", paramLabel = "YEAR")
        private int year;

        @Option(names = "--count", defaultValue = "10", paramLabel = "INTEGER", description = "How many to name.")
        private int count;

        @Option(names = "--location", paramLabel = "TEXT", description = "Limit to one elevator.")
        private String location;

        @Override
        public Integer call() {
            Map<String, BigDecimal> shares = new LinkedHashMap<>();
            try (UnitOfWork uow = main.unitOfWork()) {
                PatronageYear built = Patronage.buildCropYear(uow, year, location, null);
                for (String code : Patronage.topProducers(built, count)) {
                    shares.put(code, built.shareOfBushels(code));
                }
            }
            PrintWriter out = main.out();
            int rank = 0;
            for (Map.Entry<String, BigDecimal> share : shares.entrySet()) {
                rank++;
                out.println(right(String.valueOf(rank), 3) + "  " + left(share.getKey(), 24)
                        + right(share.getValue().toString(), 10) + "%");
            }
            if (!shares.isEmpty()) {
                BigDecimal held = shares.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                out.println("     these " + shares.size() + " hold " + held + "% of the bushels");
            }
            return Main.OK;
        }
    }

    /** The basis for the board out front. */
    @Command(name = "basis", description = "Cash less futures, for the board out front.")
    static final class Basis implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Parameters(index = "0", paramLabel = "CASH_BID", converter = Arguments.Exact.class)
        private BigDecimal cashBid;

        @Parameters(index = "1", paramLabel = "FUTURES", converter = Arguments.Exact.class)
        private BigDecimal futures;

        @Override
        public Integer call() {
            main.out().println(Desk.basis(cashBid, futures));
            return Main.OK;
        }
    }

    /** Whether the spread pays for holding the grain. */
    @Command(name = "carry", description = "Say whether the spread pays to hold the grain rather than sell it.")
    static final class Carry implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Parameters(index = "0", paramLabel = "NEAR", converter = Arguments.Exact.class)
        private BigDecimal near;

        @Parameters(index = "1", paramLabel = "DEFERRED", converter = Arguments.Exact.class)
        private BigDecimal deferred;

        @Option(names = "--months", required = true, paramLabel = "INTEGER", description = "Months between the two.")
        private int months;

        @Option(
                names = "--storage",
                required = true,
                paramLabel = "DECIMAL",
                converter = Arguments.Exact.class,
                description = "What the house charges itself per bushel per month.")
        private BigDecimal storage;

        @Override
        public Integer call() {
            BigDecimal perBushel = Desk.carry(near, deferred, months, storage);
            PrintWriter out = main.out();
            out.println("spread    " + deferred.subtract(near));
            out.println("carry     " + perBushel + " per bushel");
            out.println(perBushel.signum() > 0 ? "store" : "sell");
            return Main.OK;
        }
    }
}
