package io.github.rkaushikdev.bushelworks.domain.complaints;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/** How a complaint ended, or that it has not. */
public enum Outcome implements ValueEnum {
    OPEN("open"),
    UPHELD("upheld"),
    REJECTED("rejected"),
    SETTLED("settled");

    private final String value;

    Outcome(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static Outcome of(String value) {
        return ValueEnum.of(Outcome.class, value);
    }
}
