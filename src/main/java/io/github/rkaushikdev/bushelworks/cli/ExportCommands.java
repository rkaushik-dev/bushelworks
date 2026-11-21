package io.github.rkaushikdev.bushelworks.cli;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Quoting;
import io.github.rkaushikdev.bushelworks.Text;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.domain.settlement.Settlement;
import io.github.rkaushikdev.bushelworks.persistence.Mappers;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.ScaleTicketRow;
import io.github.rkaushikdev.bushelworks.persistence.models.SettlementRow;
import io.github.rkaushikdev.bushelworks.reports.exports.Exports;
import io.github.rkaushikdev.bushelworks.services.legacy.LegacyLoader;
import io.github.rkaushikdev.bushelworks.services.legacy.LegacyReader;
import io.github.rkaushikdev.bushelworks.services.legacy.Loaded;
import io.github.rkaushikdev.bushelworks.services.legacy.ParsedFile;
import io.github.rkaushikdev.bushelworks.services.legacy.Rejected;
import jakarta.persistence.TypedQuery;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/**
 * The export commands, and the load from the old system.
 *
 * <p>The auditor asks for three files every February and the lender asks for two of them every
 * quarter. Producing those by hand out of the reporting screen took an afternoon and produced a
 * slightly different shape each time, which is the sort of thing that turns a routine audit into a
 * long one.
 *
 * <p>Every export writes the file and then reads it straight back before it says it is done. A CSV
 * with an embedded newline in a remark column reads back with the wrong number of columns, and the
 * cheap place to find that out is here rather than six weeks later on somebody else's desk.
 */
final class ExportCommands {

    /** The commands this module adds to the group. */
    static final List<Class<?>> COMMANDS =
            List.of(ExportTickets.class, ExportSettlements.class, ExportLines.class, ImportLegacy.class);

    /** How many refused or skipped rows the load names before it stops listing them. */
    static final int LISTED = 20;

    private ExportCommands() {}

    /**
     * Read the text back, then print it or write it and say what happened.
     *
     * <p>The read back is not belt and braces. It has caught a remark with a newline in it twice, both
     * times on a ticket somebody had pasted a note into.
     */
    static void write(Main main, String text, List<String> columns, Path out, String what) {
        int rows = Exports.checkRoundTrip(text, columns);
        if (out == null) {
            main.out().print(text);
            return;
        }
        TextFiles.write(out, text);
        main.out().println(rows + " " + what + " written to " + out);
    }

    /** The live statements settled in a span, at one site or every site, in date then number order. */
    static List<Settlement> settlements(UnitOfWork uow, LocalDate opens, LocalDate closes, String location) {
        boolean oneSite = location != null && !location.isEmpty();
        TypedQuery<SettlementRow> query = uow.session()
                .createQuery(
                        "select s from SettlementRow s where s.settledOn >= :opens and s.settledOn <= :closes"
                                + " and s.voided = false" + (oneSite ? " and s.locationCode = :location" : "")
                                + " order by s.settledOn, s.number",
                        SettlementRow.class)
                .setParameter("opens", opens)
                .setParameter("closes", closes);
        if (oneSite) {
            query.setParameter("location", location);
        }
        return query.getResultList().stream().map(Mappers::settlementFromRow).toList();
    }

    /** The scale tickets as CSV. */
    @Command(name = "export-tickets", description = "Write scale tickets as CSV.")
    static final class ExportTickets implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Option(
                names = "--from",
                paramLabel = "[" + Arguments.DAY_FORMAT + "]",
                converter = Arguments.Day.class,
                description = "First scale date.")
        private LocalDate opens;

        @Option(
                names = "--to",
                paramLabel = "[" + Arguments.DAY_FORMAT + "]",
                converter = Arguments.Day.class,
                description = "Last scale date.")
        private LocalDate closes;

        @Option(names = "--location", paramLabel = "TEXT", description = "Limit to one elevator.")
        private String location;

        @Option(names = "--party", paramLabel = "TEXT", description = "Limit to one producer.")
        private String party;

        private boolean includeVoided;

        @Option(
                names = "--include-voided",
                description = "Voided tickets are left out unless the auditor asks for them.")
        void includeVoided(boolean given) {
            includeVoided |= given;
        }

        @Option(names = "--no-voided", description = "Leave the voided tickets out. The default.")
        void noVoided(boolean given) {
            includeVoided &= !given;
        }

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
                text = Exports.ticketsToCsv(tickets(uow));
            }
            write(main, text, Exports.TICKET_COLUMNS, out, "tickets");
            return Main.OK;
        }

        private List<ScaleTicketRow> tickets(UnitOfWork uow) {
            Map<String, Object> filters = new LinkedHashMap<>();
            if (opens != null) {
                filters.put("t.scaleDate >= :opens", opens);
            }
            if (closes != null) {
                filters.put("t.scaleDate <= :closes", closes);
            }
            if (location != null && !location.isEmpty()) {
                filters.put("t.locationCode = :location", location);
            }
            if (party != null && !party.isEmpty()) {
                filters.put("t.partyCode = :party", party);
            }
            List<String> where = new ArrayList<>(filters.keySet());
            if (!includeVoided) {
                where.add("t.voided = false");
            }
            String jpql = "select t from ScaleTicketRow t"
                    + (where.isEmpty() ? "" : " where " + String.join(" and ", where))
                    + " order by t.scaleDate, t.ticketNumber";
            TypedQuery<ScaleTicketRow> query = uow.session().createQuery(jpql, ScaleTicketRow.class);
            filters.forEach((clause, value) -> query.setParameter(clause.substring(clause.indexOf(':') + 1), value));
            return query.getResultList();
        }
    }

    /** The settlement headers as CSV. */
    @Command(name = "export-settlements", description = "Write settlement headers as CSV, one row each.")
    static final class ExportSettlements implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Option(
                names = "--from",
                required = true,
                paramLabel = "[" + Arguments.DAY_FORMAT + "]",
                converter = Arguments.Day.class,
                description = "First settled date.")
        private LocalDate opens;

        @Option(
                names = "--to",
                required = true,
                paramLabel = "[" + Arguments.DAY_FORMAT + "]",
                converter = Arguments.Day.class,
                description = "Last settled date.")
        private LocalDate closes;

        @Option(names = "--location", paramLabel = "TEXT", description = "Limit to one elevator.")
        private String location;

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
                text = Exports.settlementsToCsv(settlements(uow, opens, closes, location));
            }
            write(main, text, Exports.SETTLEMENT_COLUMNS, out, "settlements");
            return Main.OK;
        }
    }

    /** Every printed settlement line as CSV. */
    @Command(
            name = "export-lines",
            description = {
                "Write every printed settlement line as CSV.",
                "",
                "This is the one the auditor actually wants. The headers say what was paid; the lines say what"
                        + " it was made of, and a discount nobody can explain shows up here and nowhere else."
            })
    static final class ExportLines implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Option(
                names = "--from",
                required = true,
                paramLabel = "[" + Arguments.DAY_FORMAT + "]",
                converter = Arguments.Day.class,
                description = "First settled date.")
        private LocalDate opens;

        @Option(
                names = "--to",
                required = true,
                paramLabel = "[" + Arguments.DAY_FORMAT + "]",
                converter = Arguments.Day.class,
                description = "Last settled date.")
        private LocalDate closes;

        @Option(names = "--location", paramLabel = "TEXT", description = "Limit to one elevator.")
        private String location;

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
                text = Exports.linesToCsv(settlements(uow, opens, closes, location));
            }
            write(main, text, Exports.LINE_COLUMNS, out, "lines");
            return Main.OK;
        }
    }

    /** A night's file from the old system, read and written. */
    @Command(
            name = "import-legacy",
            description = {
                "Read a night's file from the old system and write what it holds.",
                "",
                "Dry run by default, and the dry run is not a different code path: it runs the whole load and"
                        + " rolls back. A preview that took a shortcut would be a preview of something else."
            })
    static final class ImportLegacy implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Spec
        private CommandSpec spec;

        @Parameters(index = "0", paramLabel = "PATH", converter = Arguments.ExistingFile.class)
        private Path path;

        @Option(
                names = "--map",
                paramLabel = "FILE",
                converter = Arguments.ExistingFile.class,
                description = "A file of old,new party codes, one pair per line.")
        private Path partyMap;

        private boolean commit;

        @Option(names = "--commit", description = "Write what the file holds.")
        void commit(boolean given) {
            commit |= given;
        }

        @Option(
                names = "--dry-run",
                description = "Load it and roll it back. The default: the migration is looked at before it is run.")
        void dryRun(boolean given) {
            commit &= !given;
        }

        @Option(
                names = "--assume",
                paramLabel = "FACTOR=VALUE",
                description = "Fill a grade factor the old file never had, such as bcfm=1.2.")
        private List<String> assumed = new ArrayList<>();

        @Override
        public Integer call() {
            String text = TextFiles.readLeniently(path);
            ParsedFile result = LegacyReader.parseFile(text, partyMap());
            PrintWriter out = main.out();
            out.println(LegacyReader.summary(result));
            for (Rejected item : result.rejected()
                    .subList(0, Math.min(LISTED, result.rejected().size()))) {
                main.err().println("  line " + item.lineNumber() + ": " + item.reason());
            }

            try (UnitOfWork uow = main.unitOfWork()) {
                Loaded loaded = LegacyLoader.load(uow, result, main.rules(), true, assumptions());
                out.println(LegacyLoader.loadSummary(loaded));
                for (Rejected item : loaded.skipped()
                        .subList(0, Math.min(LISTED, loaded.skipped().size()))) {
                    main.err().println("  " + item.raw() + ": " + item.reason());
                }
                if (commit) {
                    uow.commit();
                } else {
                    out.println("dry run, nothing written");
                }
            }
            return Main.OK;
        }

        /**
         * The factors the house is filling in for the whole migration, written {@code bcfm=1.2}.
         *
         * <p>A factor name nobody knows is refused rather than ignored, because a silently dropped
         * assumption is a migration that grades eleven years of tickets against nothing.
         */
        private Map<Factor, BigDecimal> assumptions() {
            Map<Factor, BigDecimal> filled = new LinkedHashMap<>();
            for (String pair : assumed) {
                int equals = pair.indexOf('=');
                String name = equals < 0 ? pair : pair.substring(0, equals);
                String value = equals < 0 ? "" : pair.substring(equals + 1);
                Factor factor;
                try {
                    factor = Factor.of(Text.trimmed(name).toLowerCase(Locale.ROOT));
                } catch (IllegalArgumentException unknown) {
                    throw refused(Quoting.quoted(name) + " is not a grade factor");
                }
                try {
                    filled.put(factor, Decimals.parse(value));
                } catch (NumberFormatException notANumber) {
                    throw refused(Quoting.quoted(value) + " is not a number");
                }
            }
            return filled;
        }

        /**
         * The old code to new code map, or none when no file was given.
         *
         * <p>The old codes are six characters and mean nothing; the house renumbered everybody at go
         * live and kept the crosswalk in a spreadsheet.
         */
        private Map<String, String> partyMap() {
            Map<String, String> mapping = new LinkedHashMap<>();
            if (partyMap == null) {
                return mapping;
            }
            for (String line : Text.lines(TextFiles.read(partyMap))) {
                String entry = Text.trimmed(line.split("#", 2)[0]);
                if (entry.isEmpty()) {
                    continue;
                }
                int comma = entry.indexOf(',');
                String code = comma < 0 ? entry : entry.substring(0, comma);
                String renumbered = comma < 0 ? "" : Text.trimmed(entry.substring(comma + 1));
                if (renumbered.isEmpty()) {
                    throw refused(Quoting.quoted(entry) + " is not an old,new pair");
                }
                mapping.put(Text.trimmed(code), renumbered);
            }
            return mapping;
        }

        private ParameterException refused(String why) {
            return new ParameterException(spec.commandLine(), "Invalid value: " + why);
        }
    }
}
