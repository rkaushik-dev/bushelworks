package io.github.rkaushikdev.bushelworks.services.legacy;

import java.util.List;

/**
 * What loading a parsed file into the ticket file actually did.
 *
 * <p>Kept apart from {@link ParsedFile} because reading a file and writing it are two different
 * failures. A row that parsed and then would not go in is a row the house has to look at by hand,
 * and it is a different pile from the ones that would not parse at all.
 *
 * @param written the ticket numbers recorded, in file order
 * @param skipped the rows left out, with why, in file order
 */
public record Loaded(List<String> written, List<Rejected> skipped) {

    public Loaded {
        written = List.copyOf(written);
        skipped = List.copyOf(skipped);
    }

    /** How many tickets went in. */
    public int count() {
        return written.size();
    }

    /** Whether nothing was left out. */
    public boolean isClean() {
        return skipped.isEmpty();
    }
}
