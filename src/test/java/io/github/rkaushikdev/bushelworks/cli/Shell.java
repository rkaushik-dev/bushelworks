package io.github.rkaushikdev.bushelworks.cli;

import io.github.rkaushikdev.bushelworks.Settings;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A terminal for the command line tests: an environment naming the database, and what a command
 * printed, on each stream and on both together in the order it was printed, the way a terminal
 * shows them.
 *
 * <pre>{@code
 * Shell.Result result = Shell.on(database).run("tickets", "--party", "HOLLIS");
 * assertThat(result.code()).isZero();
 * assertThat(result.output()).contains("0 tickets waiting");
 * }</pre>
 */
public final class Shell {

    /** The variable that names the database. */
    public static final String DATABASE_VARIABLE = Settings.PREFIX + "DATABASE_URL";

    private final Map<String, String> environment;
    private final Terminal.Databases databases;

    private Shell(Map<String, String> environment, Terminal.Databases databases) {
        this.environment = environment;
        this.databases = databases;
    }

    /**
     * A shell over a database the test already has open, such as the suite's own. Commands work on it
     * and leave it open; a command told {@code --database} opens that one instead.
     */
    public static Shell on(Database database) {
        return new Shell(Map.of(DATABASE_VARIABLE, database.url()), new Shared(database));
    }

    /** A shell whose environment names {@code url}. Each command opens it afresh and closes it. */
    public static Shell at(String url) {
        return new Shell(Map.of(DATABASE_VARIABLE, url), Terminal.Databases.FRESH);
    }

    /** A shell with nothing in its environment. A command opens whatever it is told to and closes it. */
    public static Shell plain() {
        return new Shell(Map.of(), Terminal.Databases.FRESH);
    }

    /** A shell with no database behind it: a command that asks for one fails the test. */
    public static Shell bare() {
        return new Shell(Map.of(DATABASE_VARIABLE, "jdbc:h2:mem:none"), new Refusing());
    }

    /** A database in a file of its own under {@code directory}. */
    public static String fileUrl(Path directory, String name) {
        return "jdbc:h2:file:" + directory.resolve(name);
    }

    /** Run one command line. */
    public Result run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        StringWriter both = new StringWriter();
        Terminal terminal = new Terminal(
                environment::get,
                new PrintWriter(new Tee(out, both), true),
                new PrintWriter(new Tee(err, both), true),
                databases);
        int code = Main.run(terminal, args);
        return new Result(code, out.toString(), err.toString(), both.toString());
    }

    /**
     * What one command line came back with.
     *
     * @param code the exit code
     * @param out standard output
     * @param err standard error
     * @param output both streams, interleaved as they were written
     */
    public record Result(int code, String out, String err, String output) {

        /** Both streams, a line at a time. */
        public List<String> lines() {
            return output.lines().toList();
        }
    }

    /** The test's own database for its own URL, left open afterwards; any other opened and closed. */
    private record Shared(Database database) implements Terminal.Databases {

        @Override
        public Database open(Settings settings) {
            return settings.databaseUrl().equals(database.url()) ? database : Database.open(settings);
        }

        @Override
        public void release(Database opened) {
            if (opened != database) {
                opened.close();
            }
        }
    }

    private static final class Refusing implements Terminal.Databases {

        @Override
        public Database open(Settings settings) {
            throw new AssertionError(
                    "this command should not need a database, and it asked for " + settings.databaseUrl());
        }

        @Override
        public void release(Database opened) {
            throw new AssertionError("nothing was opened to release");
        }
    }

    /** Writes everything to two writers. */
    private static final class Tee extends Writer {

        private final Writer one;
        private final Writer two;

        Tee(Writer one, Writer two) {
            this.one = one;
            this.two = two;
        }

        @Override
        public void write(char[] text, int offset, int length) throws IOException {
            one.write(text, offset, length);
            two.write(text, offset, length);
        }

        @Override
        public void flush() throws IOException {
            one.flush();
            two.flush();
        }

        @Override
        public void close() throws IOException {
            one.close();
            two.close();
        }
    }
}
