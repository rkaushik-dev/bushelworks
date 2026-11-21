package io.github.rkaushikdev.bushelworks.persistence;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.hibernate.resource.jdbc.spi.StatementInspector;

/**
 * Hands every statement the entity manager prepares to whoever is listening, and leaves it as it
 * was.
 *
 * <p>Nothing listens in service. A test that wants to know how many queries a report costs listens
 * for the length of the call, which is how a query that fires once per row gets caught before the
 * office box does.
 */
final class StatementLog implements StatementInspector {

    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    void add(Consumer<String> listener) {
        listeners.add(listener);
    }

    void remove(Consumer<String> listener) {
        listeners.remove(listener);
    }

    @Override
    public String inspect(String sql) {
        for (Consumer<String> listener : listeners) {
            listener.accept(sql);
        }
        return sql;
    }
}
