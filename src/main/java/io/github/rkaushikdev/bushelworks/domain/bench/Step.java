package io.github.rkaushikdev.bushelworks.domain.bench;

import io.github.rkaushikdev.bushelworks.ValueEnum;
import java.util.Set;

/** Where a sample is on the bench. A sample moves one step at a time and never back. */
public enum Step implements ValueEnum {
    DRAWN("drawn"),
    SPLIT("split"),
    TESTED("tested"),
    GRADED("graded"),
    DISCARDED("discarded");

    private final String value;

    Step(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    /** The steps a sample at this one may go to next. */
    public Set<Step> next() {
        return switch (this) {
            case DRAWN -> Set.of(SPLIT);
            case SPLIT -> Set.of(TESTED);
            case TESTED -> Set.of(GRADED);
            case GRADED -> Set.of(DISCARDED);
            case DISCARDED -> Set.of();
        };
    }

    public static Step of(String value) {
        return ValueEnum.of(Step.class, value);
    }
}
