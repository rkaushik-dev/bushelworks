package io.github.rkaushikdev.bushelworks.api;

import io.github.rkaushikdev.bushelworks.Settings;
import io.github.rkaushikdev.bushelworks.api.schemas.Page;
import java.util.List;
import java.util.function.Function;

/**
 * Which slice of a listing a caller asked for, clamped rather than refused.
 *
 * <p>A spreadsheet asking for ten thousand rows gets the house maximum and a {@code total} telling
 * it there is more, which is friendlier than a 422 to somebody who is not going to read one. A route
 * that lists takes {@code offset} and {@code limit} as query parameters with the defaults below and
 * hands them to {@link #clamp}.
 *
 * @param offset how many rows to skip, never below zero
 * @param limit how many rows at most, from one up to the house's page size
 */
public record Paging(int offset, int limit) {

    /** What {@code offset} is when the caller does not say. */
    public static final String DEFAULT_OFFSET = "0";

    /** What {@code limit} is when the caller does not say. */
    public static final String DEFAULT_LIMIT = "50";

    /** The slice asked for, a negative offset read as none and the limit held to the house's page size. */
    public static Paging clamp(int offset, int limit, Settings settings) {
        return new Paging(Math.max(offset, 0), Math.min(Math.max(limit, 1), settings.maxPageSize()));
    }

    /** The rows of {@code rows} in this slice; none when it starts past the end. */
    public <T> List<T> window(List<T> rows) {
        int from = Math.min(offset, rows.size());
        int to = (int) Math.min((long) offset + limit, rows.size());
        return rows.subList(from, to);
    }

    /** This slice of {@code rows}, each written out by {@code out}, with the whole listing's total. */
    public <T, R> Page<R> page(List<T> rows, Function<T, R> out) {
        return new Page<>(window(rows).stream().map(out).toList(), rows.size(), offset, limit);
    }
}
