package io.github.rkaushikdev.bushelworks.services.legacy;

import java.util.Objects;

/**
 * A row that could not be read, or could not be written, with why.
 *
 * @param lineNumber the line it was on, from one
 * @param raw the line as it stood, or the ticket number when the row was read and then refused
 * @param reason why
 */
public record Rejected(int lineNumber, String raw, String reason) {

    public Rejected {
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(reason, "reason");
    }
}
