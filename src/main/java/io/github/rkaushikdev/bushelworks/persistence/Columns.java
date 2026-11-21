package io.github.rkaushikdev.bushelworks.persistence;

import java.math.BigDecimal;

/**
 * The numeric column sizes, set once and used by every table.
 *
 * <p>Money is stored as {@code NUMERIC(14, 2)} and bushels as {@code NUMERIC(14, 4)} because the
 * settlement path is exact decimal all the way through, and a column that quietly rounds is a bug
 * that only shows up on the biggest loads. Anything that reaches a statement goes in one of these.
 */
public final class Columns {

    /** Dollars and cents. */
    public static final int MONEY_PRECISION = 14;

    public static final int MONEY_SCALE = 2;

    /** A price per bushel, a basis or a rate, to four places. */
    public static final int PRICE_PRECISION = 12;

    public static final int PRICE_SCALE = 4;

    /** Bushels, to four places. */
    public static final int BUSHELS_PRECISION = 14;

    public static final int BUSHELS_SCALE = 4;

    /** Pounds off the scale, whole. */
    public static final int POUNDS_PRECISION = 12;

    public static final int POUNDS_SCALE = 0;

    /** A percentage or a reading off a meter, to four places. */
    public static final int PERCENT_PRECISION = 7;

    public static final int PERCENT_SCALE = 4;

    private Columns() {}

    /**
     * Whatever a query handed back, as a decimal.
     *
     * <p>A sum over no rows is null and reads as zero, which is what every total here wants. A
     * decimal comes back as it is, and any other number is read from its decimal text, so {@code
     * 56.0} is {@code 56.0} and not the binary fraction nearest it.
     */
    public static BigDecimal asDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value.toString());
    }
}
