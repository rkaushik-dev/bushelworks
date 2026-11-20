package io.github.rkaushikdev.bushelworks.domain.contracts;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/** Where a contract stands. Only an open one takes delivery. */
public enum ContractStatus implements ValueEnum {
    OPEN("open"),
    FILLED("filled"),
    CLOSED("closed"),
    CANCELLED("cancelled");

    private final String value;

    ContractStatus(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static ContractStatus of(String value) {
        return ValueEnum.of(ContractStatus.class, value);
    }
}
