package io.github.rkaushikdev.bushelworks.domain.driveway;

/** The order trucks are worked in, most urgent first. Members compare in declaration order. */
public enum Priority {
    ON_SCALE(0),
    PROBED(1),
    COMPANY(2),
    WAITING(3);

    private final int value;

    Priority(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }
}
