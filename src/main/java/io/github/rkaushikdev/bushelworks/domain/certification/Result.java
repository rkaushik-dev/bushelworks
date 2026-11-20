package io.github.rkaushikdev.bushelworks.domain.certification;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/** How a scale test came out. */
public enum Result implements ValueEnum {
    PASS("pass"),
    FAIL("fail"),
    ADJUSTED("adjusted");

    private final String value;

    Result(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static Result of(String value) {
        return ValueEnum.of(Result.class, value);
    }
}
