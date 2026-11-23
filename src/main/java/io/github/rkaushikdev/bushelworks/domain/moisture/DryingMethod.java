package io.github.rkaushikdev.bushelworks.domain.moisture;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/**
 * How a drying schedule's tiers are read.
 *
 * <ul>
 *   <li>{@link #CUMULATIVE} charges each band of points at its own tier's rate, so the first two
 *       points of a wet load cost what the first two points of a damp one cost.
 *   <li>{@link #BRACKET} finds the tier the load's points fall in and charges every point at that
 *       tier's rate. It is how most drying charts by the scale window read, "four cents a point to
 *       eighteen, five cents a point over", and on a wet load it costs more than the same tiers read
 *       cumulatively.
 * </ul>
 */
public enum DryingMethod implements ValueEnum {
    CUMULATIVE("cumulative"),
    BRACKET("bracket");

    private final String value;

    DryingMethod(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static DryingMethod of(String value) {
        return ValueEnum.of(DryingMethod.class, value);
    }
}
