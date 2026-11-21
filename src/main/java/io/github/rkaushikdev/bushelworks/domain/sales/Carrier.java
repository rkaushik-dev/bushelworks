package io.github.rkaushikdev.bushelworks.domain.sales;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/** How the grain moves. */
public enum Carrier implements ValueEnum {
    TRUCK("truck"),
    RAIL("rail"),
    BARGE("barge");

    private final String value;

    Carrier(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static Carrier of(String value) {
        return ValueEnum.of(Carrier.class, value);
    }
}
