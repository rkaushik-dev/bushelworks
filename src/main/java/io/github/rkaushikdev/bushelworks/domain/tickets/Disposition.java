package io.github.rkaushikdev.bushelworks.domain.tickets;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/** What the producer asked for at the window. */
public enum Disposition implements ValueEnum {
    CASH_SALE("cash_sale"),
    STORE("store"),
    PRICE_LATER("price_later"),
    CONTRACT("contract"),
    FEED("feed"),
    TRANSFER_IN("transfer_in");

    private final String value;

    Disposition(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static Disposition of(String value) {
        return ValueEnum.of(Disposition.class, value);
    }
}
