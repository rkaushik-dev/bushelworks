package io.github.rkaushikdev.bushelworks;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Unmodifiable copies that keep the order their entries were given in.
 *
 * <p>A sample's factors, a ladder rung's limits and a statement's lines are all read back in the
 * order they were written, so a copy that reshuffles them would change what gets printed.
 */
public final class Ordered {

    private Ordered() {}

    /** An unmodifiable copy of {@code map}, in its iteration order. */
    public static <K, V> Map<K, V> copyOf(Map<? extends K, ? extends V> map) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    /** An unmodifiable copy of {@code set}, in its iteration order. */
    public static <T> Set<T> copyOf(Set<? extends T> set) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(set));
    }
}
