package io.github.rkaushikdev.bushelworks.persistence.repositories;

import io.github.rkaushikdev.bushelworks.LookupFailure;

/** No complaint with that reference on the register. */
public class ComplaintNotFound extends LookupFailure {

    public ComplaintNotFound(String message) {
        super(message);
    }
}
