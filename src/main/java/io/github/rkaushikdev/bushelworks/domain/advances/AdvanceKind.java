package io.github.rkaushikdev.bushelworks.domain.advances;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/**
 * The two things the driveway calls an advance, which behave nothing alike.
 *
 * <ul>
 *   <li>A {@link #SETTLEMENT} advance is part payment on grain the house already holds. It carries
 *       no interest, because the house has the grain.
 *   <li>A {@link #CROP} advance is a loan against grain not yet delivered. It carries interest from
 *       the day it is drawn, because the house has nothing.
 * </ul>
 */
public enum AdvanceKind implements ValueEnum {
    SETTLEMENT("settlement"),
    CROP("crop");

    private final String value;

    AdvanceKind(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static AdvanceKind of(String value) {
        return ValueEnum.of(AdvanceKind.class, value);
    }
}
