package io.github.rkaushikdev.bushelworks.domain.ledger;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/** The two sides of a journal leg. Signs are never stored; a leg is an amount and a side. */
public enum Side implements ValueEnum {
    DEBIT("debit"),
    CREDIT("credit");

    private final String value;

    Side(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    /** The other side, which is what a reversal posts to. */
    public Side opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }

    public static Side of(String value) {
        return ValueEnum.of(Side.class, value);
    }
}
