package io.github.rkaushikdev.bushelworks.domain.rulebook;

import io.github.rkaushikdev.bushelworks.ValueEnum;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How the dockage the cleaner measured becomes the dockage the ticket is settled on.
 *
 * <ul>
 *   <li>{@link #AS_MEASURED} takes the dockage off as the dockage tester read it.
 *   <li>{@link #WHOLE_AND_HALF_PERCENT} reports it the way the official standards do, in whole and
 *       half percents with any fraction under a half dropped: 1.4 percent is reported as 1.0 and
 *       1.7 as 1.5. A house that buys on official grades and sells on them has to dock the same
 *       way, or it is taking more off its producers than its buyer takes off it.
 * </ul>
 */
public enum DockageReporting implements ValueEnum {
    AS_MEASURED("as_measured"),
    WHOLE_AND_HALF_PERCENT("whole_and_half_percent");

    private static final BigDecimal HALVES = new BigDecimal("2");

    private final String value;

    DockageReporting(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static DockageReporting of(String value) {
        return ValueEnum.of(DockageReporting.class, value);
    }

    /** The dockage percent a ticket settles on, from the percent the tester measured. */
    public BigDecimal reported(BigDecimal measured) {
        return switch (this) {
            case AS_MEASURED -> measured;
            case WHOLE_AND_HALF_PERCENT ->
                measured.multiply(HALVES)
                        .setScale(0, RoundingMode.FLOOR)
                        .divide(HALVES)
                        .setScale(1);
        };
    }
}
