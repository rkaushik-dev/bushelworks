package io.github.rkaushikdev.bushelworks.domain.moisture;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/**
 * How a commodity's water comes off the bushel count.
 *
 * <p>The water method removes only the weight of the water and is what the arithmetic actually
 * supports. The table method multiplies the points of moisture by a published shrink factor
 * slightly above the water figure, so the number also covers the handling loss through the dryer.
 * A house picks one per commodity and never mixes them.
 */
public enum ShrinkMethod implements ValueEnum {
    WATER("water"),
    TABLE("table"),
    NONE("none");

    private final String value;

    ShrinkMethod(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static ShrinkMethod of(String value) {
        return ValueEnum.of(ShrinkMethod.class, value);
    }
}
