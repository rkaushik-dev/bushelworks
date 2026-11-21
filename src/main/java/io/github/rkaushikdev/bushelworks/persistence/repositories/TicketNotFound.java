package io.github.rkaushikdev.bushelworks.persistence.repositories;

import io.github.rkaushikdev.bushelworks.LookupFailure;

/** No scale ticket with that number at that site. */
public class TicketNotFound extends LookupFailure {

    public TicketNotFound(String message) {
        super(message);
    }
}
