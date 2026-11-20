package io.github.rkaushikdev.bushelworks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An enumeration whose members are written, stored and sent over the wire as a short text value
 * rather than by their Java names: {@code Factor.TEST_WEIGHT} is {@code test_weight} everywhere
 * outside the code.
 */
public interface ValueEnum {

    /** The text the member is written as. */
    String value();

    /**
     * The member of {@code type} written as {@code value}.
     *
     * @throws IllegalArgumentException naming the value and the type, when no member is written so
     */
    static <E extends Enum<E> & ValueEnum> E of(Class<E> type, String value) {
        E found = Lookup.of(type).get(value);
        if (found == null) {
            throw new IllegalArgumentException("'" + value + "' is not a valid " + type.getSimpleName());
        }
        return found;
    }

    /** Whether some member of {@code type} is written as {@code value}. */
    static <E extends Enum<E> & ValueEnum> boolean isValue(Class<E> type, String value) {
        return Lookup.of(type).containsKey(value);
    }

    /** Each value enum's members by their values, built once per type. */
    final class Lookup {

        private static final Map<Class<?>, Map<String, ?>> TABLES = new ConcurrentHashMap<>();

        private Lookup() {}

        @SuppressWarnings("unchecked") // every table is built from the type it is keyed by
        static <E extends Enum<E> & ValueEnum> Map<String, E> of(Class<E> type) {
            return (Map<String, E>) TABLES.computeIfAbsent(type, key -> table(type));
        }

        private static <E extends Enum<E> & ValueEnum> Map<String, E> table(Class<E> type) {
            Map<String, E> byValue = new java.util.HashMap<>();
            for (E member : type.getEnumConstants()) {
                byValue.put(member.value(), member);
            }
            return Map.copyOf(byValue);
        }
    }
}
