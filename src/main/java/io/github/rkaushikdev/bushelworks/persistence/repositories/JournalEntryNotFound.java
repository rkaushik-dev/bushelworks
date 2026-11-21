package io.github.rkaushikdev.bushelworks.persistence.repositories;

import io.github.rkaushikdev.bushelworks.LookupFailure;

/** No journal entry with that number. */
public class JournalEntryNotFound extends LookupFailure {

    public JournalEntryNotFound(String message) {
        super(message);
    }
}
