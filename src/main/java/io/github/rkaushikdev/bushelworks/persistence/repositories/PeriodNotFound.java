package io.github.rkaushikdev.bushelworks.persistence.repositories;

import io.github.rkaushikdev.bushelworks.LookupFailure;

/** No accounting period by that code, or none covering that day. */
public class PeriodNotFound extends LookupFailure {

    public PeriodNotFound(String message) {
        super(message);
    }
}
