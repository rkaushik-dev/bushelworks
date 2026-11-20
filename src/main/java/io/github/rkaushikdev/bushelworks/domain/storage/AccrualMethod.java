package io.github.rkaushikdev.bushelworks.domain.storage;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/**
 * How storage accrues. A house uses exactly one, filed with the state along with the rate.
 *
 * <ul>
 *   <li>{@link #DAILY} prorates the monthly rate over the days actually stored.
 *   <li>{@link #ANNIVERSARY} charges a whole month every time the day of the month the grain
 *       arrived comes round again, so a bin emptied one day late pays for a month.
 *   <li>{@link #CALENDAR} charges a whole month for every calendar month touched, which makes grain
 *       delivered on the thirty first of October pay for October.
 * </ul>
 */
public enum AccrualMethod implements ValueEnum {
    DAILY("daily"),
    ANNIVERSARY("anniversary"),
    CALENDAR("calendar");

    private final String value;

    AccrualMethod(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static AccrualMethod of(String value) {
        return ValueEnum.of(AccrualMethod.class, value);
    }
}
