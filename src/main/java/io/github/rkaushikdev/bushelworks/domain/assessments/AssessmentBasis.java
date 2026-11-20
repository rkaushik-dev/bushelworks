package io.github.rkaushikdev.bushelworks.domain.assessments;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/** What an assessment's rate is a rate of. */
public enum AssessmentBasis implements ValueEnum {
    PER_BUSHEL("per_bushel"),
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
