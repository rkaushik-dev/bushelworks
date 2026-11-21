package io.github.rkaushikdev.bushelworks.services.seeding;

/**
 * What seeding put in.
 *
 * @param parties producers opened
 * @param locations sites written
 * @param bins bins written
 * @param tickets loads recorded
 */
public record Seeded(int parties, int locations, int bins, int tickets) {

    /** Whether no load went in. */
    public boolean isEmpty() {
        return tickets == 0;
    }
}
