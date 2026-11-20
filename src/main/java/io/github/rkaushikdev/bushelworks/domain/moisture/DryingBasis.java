package io.github.rkaushikdev.bushelworks.domain.moisture;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/** Which bushel count the per point drying rate is applied to. */
public enum DryingBasis implements ValueEnum {
    WET("wet"),
    DRY("dry");

    private final String value;

    DryingBasis(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static DryingBasis of(String value) {
        return ValueEnum.of(DryingBasis.class, value);
    }
}
