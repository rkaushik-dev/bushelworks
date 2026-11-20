package io.github.rkaushikdev.bushelworks.domain.grade;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/** The rungs of a grade ladder, best first. Ordering is by {@link #rank()}, not by name. */
public enum NumericalGrade implements ValueEnum {
    US_1("1", 1),
    US_2("2", 2),
    US_3("3", 3),
    US_4("4", 4),
    US_5("5", 5),
    SAMPLE("SAMPLE", 99);

    private final String value;
    private final int rank;

    NumericalGrade(String value, int rank) {
        this.value = value;
        this.rank = rank;
    }

    @Override
    public String value() {
        return value;
    }

    /** One for a No. 1, larger for worse; Sample Grade is below every number. */
    public int rank() {
        return rank;
    }

    public boolean isBetterThan(NumericalGrade other) {
        return rank < other.rank;
    }

    public static NumericalGrade of(String value) {
        return ValueEnum.of(NumericalGrade.class, value);
    }
}
