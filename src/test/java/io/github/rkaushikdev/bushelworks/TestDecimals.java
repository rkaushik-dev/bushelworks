package io.github.rkaushikdev.bushelworks;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/** Decimal literals for tests, written the way the office writes them. */
public final class TestDecimals {

    private TestDecimals() {}

    /** The decimal {@code text} spells, places and all. */
    public static BigDecimal d(String text) {
        return new BigDecimal(text);
    }

    /** A whole number as a decimal. */
    public static BigDecimal d(long value) {
        return BigDecimal.valueOf(value);
    }

    /** Several decimals, in order. */
    public static List<BigDecimal> ds(String... texts) {
        return Arrays.stream(texts).map(BigDecimal::new).toList();
    }

    /** The sum of several decimals, exactly. */
    public static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
