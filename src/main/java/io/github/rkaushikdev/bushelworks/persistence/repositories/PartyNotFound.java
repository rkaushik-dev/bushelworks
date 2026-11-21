package io.github.rkaushikdev.bushelworks.persistence.repositories;

import io.github.rkaushikdev.bushelworks.LookupFailure;

/** No party with that code. */
public class PartyNotFound extends LookupFailure {

    public PartyNotFound(String message) {
        super(message);
    }
}
