package io.github.rkaushikdev.bushelworks.persistence;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Counts the statements a database's sessions prepare while it is armed.
 *
 * <p>{@link #selecting} matches {@code FROM <table>} on a word boundary, whatever the case, so a
 * read of {@code settlement_line} is not counted as a read of {@code settlement}. A count that
 * matched on the bare name would come out high in every query test.
 *
 * <pre>{@code
 * Counter counter = Counter.during(database, () -> uow.settlements().inWindow(opens, closes));
 * assertThat(counter.selecting("settlement")).isEqualTo(1);
 *
 * try (Counter counter = Counter.start(database)) {
 *     uow.settlements().lineTotals(ids);
 *     assertThat(counter.selecting("settlement_line")).isEqualTo(1);
 * }
 * }</pre>
 */
public final class Counter implements AutoCloseable {

    private final Database database;
    private final List<String> statements = new CopyOnWriteArrayList<>();
    private final Consumer<String> listener = statements::add;

    private Counter(Database database) {
        this.database = database;
    }

    /** A counter, armed until it is closed. */
    public static Counter start(Database database) {
        Counter counter = new Counter(database);
        database.addStatementListener(counter.listener);
        return counter;
    }

    /** Count the statements {@code work} costs; the counter is disarmed when it returns. */
    public static Counter during(Database database, Runnable work) {
        Counter counter = start(database);
        try {
            work.run();
        } finally {
            counter.close();
        }
        return counter;
    }

    /** Every statement seen, in order. */
    public List<String> statements() {
        return List.copyOf(statements);
    }

    public int count() {
        return statements.size();
    }

    /** The statements that read the named table. */
    public int selecting(String table) {
        Pattern reads = Pattern.compile("FROM " + Pattern.quote(table) + "\\b", Pattern.CASE_INSENSITIVE);
        return (int)
                statements.stream().filter(sql -> reads.matcher(sql).find()).count();
    }

    /** Stop counting. What was counted stays readable. */
    @Override
    public void close() {
        database.removeStatementListener(listener);
    }
}
