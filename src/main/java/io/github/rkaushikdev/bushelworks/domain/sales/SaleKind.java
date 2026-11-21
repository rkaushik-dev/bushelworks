package io.github.rkaushikdev.bushelworks.domain.sales;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/** What kind of sale it is. */
public enum SaleKind implements ValueEnum {
    SPOT("spot"),
    FORWARD("forward"),
    BASIS("basis");

    private final String value;

    SaleKind(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static SaleKind of(String value) {
        return ValueEnum.of(SaleKind.class, value);
    }
}
