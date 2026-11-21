package io.github.rkaushikdev.bushelworks.services.dayend;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Everything a daily close takes beyond the day.
 *
 * <pre>{@code
 * DayEnd.runClose(uow, day, CloseOptions.defaults().withLocationCode("HOME").withDryRun(true));
 * }</pre>
 *
 * @param locationCode the site to close, or {@link #EVERY_SITE} for the whole house
 * @param positionTolerance the most a commodity's net long may move overnight without being flagged
 * @param dryRun whether to build every journal entry and report what would refuse, writing none
 */
public record CloseOptions(String locationCode, BigDecimal positionTolerance, boolean dryRun) {

    /** The location code that closes the whole house. */
    public static final String EVERY_SITE = "*";

    /** A quarter of a million bushels: more than that overnight is worth a question. */
    public static final BigDecimal POSITION_TOLERANCE = new BigDecimal("250000");

    public CloseOptions {
        Objects.requireNonNull(locationCode, "locationCode");
        Objects.requireNonNull(positionTolerance, "positionTolerance");
    }

    /** The whole house, the standard tolerance, posting for real. */
    public static CloseOptions defaults() {
        return new CloseOptions(EVERY_SITE, POSITION_TOLERANCE, false);
    }

    public CloseOptions withLocationCode(String value) {
        return new CloseOptions(value, positionTolerance, dryRun);
    }

    public CloseOptions withPositionTolerance(BigDecimal value) {
        return new CloseOptions(locationCode, value, dryRun);
    }

    public CloseOptions withDryRun(boolean value) {
        return new CloseOptions(locationCode, positionTolerance, value);
    }
}
