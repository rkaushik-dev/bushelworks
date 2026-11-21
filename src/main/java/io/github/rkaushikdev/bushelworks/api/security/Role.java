package io.github.rkaushikdev.bushelworks.api.security;

import io.github.rkaushikdev.bushelworks.ValueEnum;

/**
 * What a key is allowed to do. The split is by what a mistake costs rather than by job title.
 *
 * <ul>
 *   <li>{@link #READ} can see anything but change nothing
 *   <li>{@link #SCALE} can write tickets, which is what the driveway needs
 *   <li>{@link #OFFICE} can settle, pay and amend
 * </ul>
 */
public enum Role implements ValueEnum {
    READ("read", 0),
    SCALE("scale", 1),
    OFFICE("office", 2);

    private final String value;
    private final int rank;

    Role(String value, int rank) {
        this.value = value;
        this.rank = rank;
    }

    @Override
    public String value() {
        return value;
    }

    /** How senior the role is: a role can do everything a lower one can. */
    public int rank() {
        return rank;
    }

    public static Role of(String value) {
        return ValueEnum.of(Role.class, value);
    }
}
