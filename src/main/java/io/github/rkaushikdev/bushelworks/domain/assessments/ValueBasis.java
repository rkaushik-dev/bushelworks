package io.github.rkaushikdev.bushelworks.domain.assessments;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/** Which value a percentage assessment is levied on. */
public enum ValueBasis implements ValueEnum {
    GROSS("gross"),
    NET_OF_DISCOUNTS("net_of_discounts");

    private final String value;

    ValueBasis(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static ValueBasis of(String value) {
        return ValueEnum.of(ValueBasis.class, value);
    }
}
