package io.github.rkaushikdev.bushelworks.domain.tickets;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/** Why a party has a share of a load. */
public enum SplitRole implements ValueEnum {
    OWNER("owner"),
    LANDLORD("landlord"),
    TENANT("tenant"),
    LIENHOLDER("lienholder");

    private final String value;

    SplitRole(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static SplitRole of(String value) {
        return ValueEnum.of(SplitRole.class, value);
    }
}
