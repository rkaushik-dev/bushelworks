package io.github.rkaushikdev.bushelworks.devices.scale;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/** The indicator's status byte: settled, still moving, or past what the scale can weigh. */
public enum Stability implements ValueEnum {
    STABLE("S"),
    MOTION("M"),
    OVER_CAPACITY("O");

    private final String value;

    Stability(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static Stability of(String value) {
        return ValueEnum.of(Stability.class, value);
    }
}
