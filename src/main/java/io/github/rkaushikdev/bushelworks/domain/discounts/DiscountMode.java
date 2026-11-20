package io.github.rkaushikdev.bushelworks.domain.discounts;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/**
 * How a band counts its increment.
 *
 * <ul>
 *   <li>{@link #STEP} charges a whole increment the moment any part of it is crossed, so 0.1 over
 *       on a 0.5 increment costs the same as 0.5 over. This is what a printed discount table means
 *       when it says "per half pound or fraction thereof".
 *   <li>{@link #PRORATE} charges the exact fraction. Rare on the driveway, common in the contract
 *       with the terminal.
 *   <li>{@link #FLAT} charges the rate once, no matter how far over the load is.
 * </ul>
 */
public enum DiscountMode implements ValueEnum {
    STEP("step"),
    PRORATE("prorate"),
    FLAT("flat");

    private final String value;

    DiscountMode(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static DiscountMode of(String value) {
        return ValueEnum.of(DiscountMode.class, value);
    }
}
