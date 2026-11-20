package io.github.rkaushikdev.bushelworks.domain.freight;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/** Who pays for the haul. */
public enum PaidBy implements ValueEnum {
    HOUSE("house"),
    PRODUCER("producer"),
    BUYER("buyer");

    private final String value;

    PaidBy(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static PaidBy of(String value) {
        return ValueEnum.of(PaidBy.class, value);
    }
}
