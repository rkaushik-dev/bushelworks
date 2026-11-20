package io.github.rkaushikdev.bushelworks.domain.commodity;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/** What the posted price is quoted per. */
public enum PriceUnit implements ValueEnum {
    BUSHEL("BU"),
    HUNDREDWEIGHT("CWT"),
    TON("TON");

    private final String value;

    PriceUnit(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static PriceUnit of(String value) {
        return ValueEnum.of(PriceUnit.class, value);
    }
}
