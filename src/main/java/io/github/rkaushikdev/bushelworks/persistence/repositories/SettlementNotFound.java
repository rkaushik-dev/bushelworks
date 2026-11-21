package io.github.rkaushikdev.bushelworks.persistence.repositories;

import io.github.rkaushikdev.bushelworks.LookupFailure;

/** No settlement with that number. */
public class SettlementNotFound extends LookupFailure {

    public SettlementNotFound(String message) {
        super(message);
    }
}
