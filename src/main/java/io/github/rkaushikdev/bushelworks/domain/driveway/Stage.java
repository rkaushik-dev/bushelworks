package io.github.rkaushikdev.bushelworks.domain.driveway;

import io.github.rkaushikdev.bushelworks.ValueEnum;
import java.util.Set;

/**
 * Where a truck is in the process. Nothing skips a stage and nothing goes backwards; a truck that
 * has to be reworked leaves the queue and rejoins it.
 */
public enum Stage implements ValueEnum {
    ARRIVED("arrived"),
    PROBED("probed"),
    ON_SCALE("on_scale"),
    DUMPING("dumping"),
    DONE("done");

    private final String value;

    Stage(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }

    /** What a truck at this stage may do next. */
    public Set<Stage> next() {
        return switch (this) {
            case ARRIVED -> Set.of(PROBED);
            case PROBED -> Set.of(ON_SCALE);
            case ON_SCALE -> Set.of(DUMPING);
            case DUMPING -> Set.of(DONE);
            case DONE -> Set.of();
        };
    }

    public static Stage of(String value) {
        return ValueEnum.of(Stage.class, value);
    }
}
