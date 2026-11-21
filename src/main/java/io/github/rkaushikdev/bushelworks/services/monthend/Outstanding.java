package io.github.rkaushikdev.bushelworks.services.monthend;

import java.util.Objects;

/**
 * One thing standing between a month and its close.
 *
 * @param kind which check found it: {@code unposted}, {@code journal}, {@code closed} or {@code
 *     sequence}
 * @param detail what was found, in a sentence the office can act on
 */
public record Outstanding(String kind, String detail) {

    public Outstanding {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(detail, "detail");
    }

    /** The kind and the detail, as a refusal quotes them. */
    @Override
    public String toString() {
        return kind + ": " + detail;
    }
}
