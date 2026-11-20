package io.github.rkaushikdev.bushelworks.domain.commodity;

import io.github.rkaushikdev.bushelworks.LookupFailure;

/** Raised when a ticket names a commodity the house does not buy. */
public class UnknownCommodity extends LookupFailure {

    public UnknownCommodity(String message) {
        super(message);
    }
}
