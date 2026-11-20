package io.github.rkaushikdev.bushelworks.domain.freight;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/**
 * What a freight rate is quoted per.
 *
 * <p>Per loaded mile is what an owner operator quotes, per bushel is what the terminal quotes back,
 * and per hundredweight is what the feedlot quotes because it buys by weight. They are not
 * interchangeable without the distance and the weight in front of you.
 */
public enum RateBasis implements ValueEnum {
    PER_MILE("per_mile"),
    PER_BUSHEL("per_bushel"),
    PER_HUNDREDWEIGHT("per_cwt"),
    FLAT("flat");

    private final String value;

    RateBasis(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static RateBasis of(String value) {
        return ValueEnum.of(RateBasis.class, value);
    }
}
