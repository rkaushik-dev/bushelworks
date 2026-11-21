package io.github.rkaushikdev.bushelworks.reports.spool;

import java.util.Objects;

/**
 * One thing to print, with who it belongs to.
 *
 * @param name what the document is called, usually its number; the file it is written to is named
 *     after it
 * @param partyCode the producer it goes to
 * @param text the rendered document, lines ending in a line feed
 */
public record SpoolDocument(String name, String partyCode, String text) {

    public SpoolDocument {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(partyCode, "partyCode");
        Objects.requireNonNull(text, "text");
        if (name.isEmpty()) {
            throw new SpoolError("a document needs a name");
        }
        if (text.isBlank()) {
            throw new SpoolError(name + ": nothing to print");
        }
    }

    /** How many lines the text runs to, counting a last empty one after a final line feed. */
    public int lines() {
        return text.split("\n", -1).length;
    }

    /** How many pages of {@link Spool#PAGE_LINES} it takes, never fewer than one. */
    public int pages() {
        return Math.max(1, Math.ceilDiv(lines(), Spool.PAGE_LINES));
    }
}
