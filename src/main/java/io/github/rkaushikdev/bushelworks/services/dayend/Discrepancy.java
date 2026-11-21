package io.github.rkaushikdev.bushelworks.services.dayend;

import java.util.Objects;

/**
 * One thing that did not tie at the close.
 *
 * @param kind what kind of check found it: {@code ticket numbering}, {@code settlement}, {@code
 *     period}, {@code journal} or {@code position}
 * @param detail what was found, in a sentence the office can act on
 */
public record Discrepancy(String kind, String detail) {

    public Discrepancy {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(detail, "detail");
    }

    /** The kind and the detail, as the day book prints them. */
    @Override
    public String toString() {
        return kind + ": " + detail;
    }
}
