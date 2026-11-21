package io.github.rkaushikdev.bushelworks.reports.fulfilment;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/**
 * Where a contract stands against its delivery window.
 *
 * <ul>
 *   <li>{@link #FILLED} is inside tolerance and needs nothing.
 *   <li>{@link #ON_TRACK} is open, and the producer's hauling rate covers what is outstanding.
 *   <li>{@link #AT_RISK} is open with more bushels outstanding than the producer has been hauling
 *       per week times the weeks left. This is the useful one: it turns into a phone call while there
 *       is still time to do something.
 *   <li>{@link #SHORT} is past its window with bushels still outstanding, which is a default and a
 *       conversation with somebody senior.
 * </ul>
 */
public enum FulfilmentState implements ValueEnum {
    FILLED("filled"),
    ON_TRACK("on_track"),
    AT_RISK("at_risk"),
    SHORT("short");

    private final String value;

    FulfilmentState(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static FulfilmentState of(String value) {
        return ValueEnum.of(FulfilmentState.class, value);
    }
}
