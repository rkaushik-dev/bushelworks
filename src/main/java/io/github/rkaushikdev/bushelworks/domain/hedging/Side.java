package io.github.rkaushikdev.bushelworks.domain.hedging;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/** Which way a lot is on. Short is the elevator's normal state. */
public enum Side implements ValueEnum {
    SHORT("short"),
    LONG("long");

    private final String value;

    Side(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static Side of(String value) {
        return ValueEnum.of(Side.class, value);
    }
}
