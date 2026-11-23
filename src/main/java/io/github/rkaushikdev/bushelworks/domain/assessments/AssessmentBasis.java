package io.github.rkaushikdev.bushelworks.domain.assessments;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/**
 * What an assessment's rate is a rate of.
 *
 * <p>Most checkoffs are so much a bushel. Sunflower and the other seeds bought by weight are levied
 * by the hundredweight, which is the commodity's bushels at its standard test weight over a
 * hundred, and the soybean order is a percentage of the value.
 */
public enum AssessmentBasis implements ValueEnum {
    PER_BUSHEL("per_bushel"),
    PER_HUNDREDWEIGHT("per_hundredweight"),
    PERCENT_OF_VALUE("percent_of_value");

    private final String value;

    AssessmentBasis(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    public static AssessmentBasis of(String value) {
        return ValueEnum.of(AssessmentBasis.class, value);
    }
}
