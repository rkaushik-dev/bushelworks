package io.github.rkaushikdev.bushelworks.api;

import java.util.List;
import java.util.Objects;

/**
 * One thing wrong with a request, and where it was found.
 *
 * @param location where: the part of the request ({@code body}, {@code query}, {@code path}) and then
 *     the field names and list positions down to the value, as {@code ["body", "splits", 0,
 *     "percent"]}
 * @param message what was wrong with it, as the caller is shown it
 */
public record Refusal(List<Object> location, String message) {

    public Refusal {
        location = List.copyOf(location);
        Objects.requireNonNull(message, "message");
    }

    /** A refusal of one named field in one part of the request. */
    public static Refusal of(String part, String field, String message) {
        return new Refusal(List.of(part, field), message);
    }

    /** Where, as the caller reads it: {@code splits.0.percent}. */
    public String field() {
        return Errors.fieldPath(location);
    }
}
