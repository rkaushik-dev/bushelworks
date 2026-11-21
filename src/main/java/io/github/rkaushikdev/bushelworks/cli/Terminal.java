package io.github.rkaushikdev.bushelworks.cli;

import io.github.rkaushikdev.bushelworks.Settings;
import io.github.rkaushikdev.bushelworks.persistence.Database;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.function.UnaryOperator;

/**
 * What a command talks to: the environment it was started in, its two streams, and the way it gets
 * hold of a database.
 *
 * <p>A command's answer goes to {@code out}. A refusal goes to {@code err} as one line, and so do the
 * house's own log lines, so a batch file that keeps standard output keeps only the answers.
 *
 * <p>There is no keyboard. Nothing here asks a question: the office runs these out of a batch file at
 * six in the morning and there is nobody at the keyboard to answer one.
 *
 * @param environment the value of a variable, or null when it is not set
 * @param out where the command's answer goes
 * @param err where a refusal and the log go
 * @param databases how the settings' database is opened, and let go of afterwards
 */
public record Terminal(UnaryOperator<String> environment, PrintWriter out, PrintWriter err, Databases databases) {

    /** The process's own environment and streams, opening the database afresh for each command. */
    public static Terminal system() {
        Charset charset = Charset.defaultCharset();
        return new Terminal(
                System.getenv()::get,
                new PrintWriter(System.out, true, charset),
                new PrintWriter(System.err, true, charset),
                Databases.FRESH);
    }

    /** How a command gets the database its settings name, and gives it back. */
    public interface Databases {

        /** Open the database for the command and close it when the command is done. */
        Databases FRESH = new Databases() {
            @Override
            public Database open(Settings settings) {
                return Database.open(settings);
            }

            @Override
            public void release(Database database) {
                database.close();
            }
        };

        /** The database {@code settings} name. Nothing connects until something is asked of it. */
        Database open(Settings settings);

        /** The command is done with it. */
        void release(Database database);
    }
}
