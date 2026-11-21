package io.github.rkaushikdev.bushelworks.services.charges;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/**
 * Which bushel count quality discounts are extended over.
 *
 * <p>A filed policy, not an arithmetic fact. Discounting the wet bushels costs the producer more
 * than discounting the dry ones and both are in use.
 */
public enum DiscountBasis implements ValueEnum {
    WET("wet"),
    DRY("dry");

    private final String value;

    DiscountBasis(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static DiscountBasis of(String value) {
        return ValueEnum.of(DiscountBasis.class, value);
    }
}
