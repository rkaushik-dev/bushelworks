package io.github.rkaushikdev.bushelworks.cli;

import io.github.rkaushikdev.bushelworks.Bushelworks;
import io.github.rkaushikdev.bushelworks.BushelworksError;
import io.github.rkaushikdev.bushelworks.HouseLog;
import io.github.rkaushikdev.bushelworks.Settings;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.release.Release;
import io.github.rkaushikdev.bushelworks.release.Releases;
import io.github.rkaushikdev.bushelworks.services.houses.Houses;
import io.github.rkaushikdev.bushelworks.services.seeding.Seeded;
import io.github.rkaushikdev.bushelworks.services.seeding.Seeding;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;

/**
 * The command line, which is how the grain accountant actually works.
 *
 * <p>The touch screen in the scale house is for the driveway. Month end, the storage run, the
 * position report and anything that needs to be piped into a spreadsheet happen here.
 *
 * <p>This class is the group and the commands that belong to no module of their own: building the
 * schema, the release notes and the demonstration house. The rest live in {@link GrainCommands}, and
 * {@link Serve} runs the HTTP service.
 *
 * <ul>
 *   <li>Every command opens its own unit of work and commits at the end or not at all.
 *   <li>Nothing is interactive: the office runs these out of a batch file at six in the morning and
 *       there is nobody at the keyboard to answer a prompt.
 *   <li>The exit code is the answer: {@link #OK}, {@link #REFUSED} when the house refused, with the
 *       error's code and sentence on one line of standard error, which is what the grain accountant
 *       reads out over the telephone, and {@link #MISUSED} for arguments that could not be read.
 *   <li>The house log is pointed at standard error, at the level the settings ask for, before any
 *       command runs.
 * </ul>
 *
 * <p>No command but {@code serve} starts Spring. Each opens the database its settings name the first
 * time it needs it and closes it when it is done, so {@code --database} is honoured before anything
 * connects and the commands that need no database never open one.
 */
@Command(
        name = "bushelworks",
        version = Bushelworks.VERSION,
        description = "Scale house capture and settlement.",
        sortOptions = false)
public final class Main implements Callable<Integer> {

    /** It worked. */
    public static final int OK = 0;

    /** The house refused: a domain error, reported as its code and its sentence. */
    public static final int REFUSED = 1;

    /** The arguments could not be read. */
    public static final int MISUSED = 2;

    /** The commands that hang off the group, each from the module it belongs to. */
    static final List<Class<?>> COMMANDS =
            commands(List.of(InitDb.class, Changes.class, Seed.class, Serve.class), GrainCommands.COMMANDS);

    private final Terminal terminal;

    @Option(
            names = {"-h", "--help"},
            usageHelp = true,
            scope = ScopeType.INHERIT,
            description = "Show this message and exit.")
    private boolean helpAsked;

    @Option(names = "--version", versionHelp = true, description = "Show the version and exit.")
    private boolean versionAsked;

    @Option(names = "--database", paramLabel = "TEXT", description = "Override the database url for this run.")
    private String databaseUrl;

    @Spec
    private CommandSpec spec;

    private Settings settings;
    private RuleBook rules;
    private Database opened;

    Main(Terminal terminal) {
        this.terminal = terminal;
    }

    /** The console entry point. */
    public static void main(String... args) {
        System.exit(run(Terminal.system(), args));
    }

    /** Run one command line and hand back its exit code. */
    public static int run(Terminal terminal, String... args) {
        Main main = new Main(terminal);
        try {
            return main.commandLine().execute(args);
        } finally {
            main.close();
            terminal.out().flush();
            terminal.err().flush();
        }
    }

    /** Every command's name and the first line of what it says it does, in name order. */
    public static SortedMap<String, String> commandSummaries() {
        SortedMap<String, String> summaries = new TreeMap<>();
        for (Class<?> each : COMMANDS) {
            Command command = each.getAnnotation(Command.class);
            summaries.put(command.name(), command.description()[0]);
        }
        return Collections.unmodifiableSortedMap(summaries);
    }

    /** Without a command, the group says what it has. */
    @Override
    public Integer call() {
        spec.commandLine().usage(terminal.out());
        return OK;
    }

    private CommandLine commandLine() {
        CommandLine command = new CommandLine(this);
        COMMANDS.forEach(command::addSubcommand);
        // Settings reach the subcommands added before them, so they come after.
        return command.setExpandAtFiles(false)
                .setOverwrittenOptionsAllowed(true)
                .setOut(terminal.out())
                .setErr(terminal.err())
                .setExecutionStrategy(this::execute)
                .setExecutionExceptionHandler((trouble, line, parsed) -> refused(trouble));
    }

    /** Point the house log at the terminal before a command runs, as every command expects. */
    private int execute(CommandLine.ParseResult parsed) {
        if (parsed.hasSubcommand()) {
            try {
                HouseLog.configure(settings().logLevel(), new WriterStream(terminal.err()));
            } catch (BushelworksError refusal) {
                return refused(refusal);
            }
        }
        return new CommandLine.RunLast().execute(parsed);
    }

    private int refused(Exception trouble) throws Exception {
        if (trouble instanceof BushelworksError refusal) {
            return refused(refusal);
        }
        throw trouble;
    }

    private int refused(BushelworksError refusal) {
        terminal.err().println(refusal.code() + ": " + refusal.getMessage());
        return REFUSED;
    }

    /** Where a command's answer goes. */
    PrintWriter out() {
        return terminal.out();
    }

    /** Where a command's complaint goes. */
    PrintWriter err() {
        return terminal.err();
    }

    Terminal terminal() {
        return terminal;
    }

    /** The settings, read from the environment once, with {@code --database} laid over them. */
    Settings settings() {
        if (settings == null) {
            Settings loaded = Settings.load(terminal.environment());
            settings = databaseUrl == null || databaseUrl.isEmpty() ? loaded : loaded.withDatabaseUrl(databaseUrl);
        }
        return settings;
    }

    /** The house rule book for the settings. */
    RuleBook rules() {
        if (rules == null) {
            rules = Houses.houseRulebook(settings());
        }
        return rules;
    }

    /** The database the settings name, opened the first time a command asks for it. */
    Database database() {
        if (opened == null) {
            opened = terminal.databases().open(settings());
        }
        return opened;
    }

    /** A unit of work on the database. It rolls back unless the command commits it. */
    UnitOfWork unitOfWork() {
        return UnitOfWork.open(database());
    }

    private void close() {
        if (opened != null) {
            terminal.databases().release(opened);
            opened = null;
        }
    }

    @SafeVarargs
    private static List<Class<?>> commands(List<Class<?>>... modules) {
        List<Class<?>> all = new ArrayList<>();
        for (List<Class<?>> module : modules) {
            all.addAll(module);
        }
        // Listed by name, the way the help shows them.
        all.sort(Comparator.comparing(
                (Class<?> each) -> each.getAnnotation(Command.class).name()));
        return List.copyOf(all);
    }

    /** Build the schema in the database. */
    @Command(name = "init-db", description = "Build the schema, for a scratch database.")
    static final class InitDb implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Override
        public Integer call() {
            main.database().migrations().upgrade();
            main.out().println("schema built at " + main.settings().databaseUrl());
            return OK;
        }
    }

    /** The release notes. */
    @Command(name = "changes", description = "What changed, so the house can read it before it upgrades.")
    static final class Changes implements Callable<Integer> {

        @Option(names = "--since", paramLabel = "TEXT", description = "Only what was released after this version.")
        private String fromVersion;

        private boolean breakingOnly;

        @Option(names = "--breaking", description = "Only the lines somebody has to be telephoned about.")
        void breaking(boolean given) {
            breakingOnly |= given;
        }

        @Option(names = "--all", description = "Everything, breaking or not. The default.")
        void all(boolean given) {
            breakingOnly &= !given;
        }

        @ParentCommand
        private Main main;

        @Override
        public Integer call() {
            PrintWriter out = main.out();
            List<Release> entries = fromVersion == null ? Releases.HISTORY : Releases.since(fromVersion);
            if (breakingOnly) {
                List<String> lines =
                        Releases.breakingSince(fromVersion == null || fromVersion.isEmpty() ? "0.0.0" : fromVersion);
                lines.forEach(out::println);
                if (lines.isEmpty()) {
                    out.println("nothing breaking");
                }
                return OK;
            }
            if (entries.isEmpty()) {
                out.println("nothing since then");
                return OK;
            }
            out.println(Releases.renderHistory(entries));
            if (fromVersion != null) {
                List<String> migrations = Releases.migrationsSince(fromVersion);
                if (!migrations.isEmpty()) {
                    out.println();
                    out.println("this upgrade runs " + migrations.size() + " migrations");
                }
            }
            return OK;
        }
    }

    /** A demonstration house. */
    @Command(name = "seed", description = "Fill an empty database with a week of a working elevator.")
    static final class Seed implements Callable<Integer> {

        private boolean force;

        @Option(names = "--force", description = "Seed even if there is already a producer file here.")
        void force(boolean given) {
            force |= given;
        }

        @Option(names = "--no-force", description = "Refuse a database that already has producers. The default.")
        void noForce(boolean given) {
            force &= !given;
        }

        @ParentCommand
        private Main main;

        @Override
        public Integer call() {
            String summary;
            try (UnitOfWork uow = main.unitOfWork()) {
                Seeded result = Seeding.seed(uow, main.rules(), force);
                summary = Seeding.describe(result);
                uow.commit();
            }
            main.out().println(summary);
            return OK;
        }
    }
}
