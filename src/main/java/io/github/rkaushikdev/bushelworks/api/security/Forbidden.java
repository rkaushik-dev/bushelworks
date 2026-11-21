package io.github.rkaushikdev.bushelworks.api.security;

import io.github.rkaushikdev.bushelworks.BushelworksError;
import io.github.rkaushikdev.bushelworks.ErrorCode;

/** A key that is real but not senior enough for what it asked. Answered with a 403. */
@ErrorCode("forbidden")
public class Forbidden extends BushelworksError {

    public Forbidden(String message) {
        super(message);
    }
}
