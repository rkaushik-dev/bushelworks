package io.github.rkaushikdev.bushelworks.domain.complaints;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/** Who rang up about the grain. */
public enum Source implements ValueEnum {
    BUYER("buyer"),
    PRODUCER("producer"),
    HAULIER("haulier");

    private final String value;

    Source(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static Source of(String value) {
        return ValueEnum.of(Source.class, value);
    }
}
