package io.github.rkaushikdev.bushelworks.domain.commodity;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/** Which way a factor has to move before it costs the producer money. */
public enum FactorDirection implements ValueEnum {
    HIGH_IS_BAD("high_is_bad"),
    LOW_IS_BAD("low_is_bad");

    private final String value;

    FactorDirection(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }
}
