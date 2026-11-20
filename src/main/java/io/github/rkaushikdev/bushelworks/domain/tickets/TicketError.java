package io.github.rkaushikdev.bushelworks.domain.tickets;

import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a ticket cannot stand as written. */
public class TicketError extends ValidationError {

    public TicketError(String message) {
        super(message);
    }
}
