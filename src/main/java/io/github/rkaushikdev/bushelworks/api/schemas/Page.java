package io.github.rkaushikdev.bushelworks.api.schemas;

import java.util.List;

/**
 * A slice of a listing, with enough to ask for the next one.
 *
 * @param items the rows in this slice
 * @param total how many rows the whole listing has
 * @param offset how many rows came before this slice
 * @param limit the most rows a slice carries
 */
public record Page<T>(List<T> items, int total, int offset, int limit) {

    public Page {
        items = List.copyOf(items);
    }

    /** Whether there are rows after this slice. */
    public boolean hasMore() {
        return offset + items.size() < total;
    }
}
