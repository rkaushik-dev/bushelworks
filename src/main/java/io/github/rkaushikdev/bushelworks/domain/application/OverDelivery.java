package io.github.rkaushikdev.bushelworks.domain.application;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/** What to do with bushels past the last open contract. */
public enum OverDelivery implements ValueEnum {
    REFUSE("refuse"),
    SPOT("spot"),
    STORE("store");

    private final String value;

    OverDelivery(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static OverDelivery of(String value) {
        return ValueEnum.of(OverDelivery.class, value);
    }
}
