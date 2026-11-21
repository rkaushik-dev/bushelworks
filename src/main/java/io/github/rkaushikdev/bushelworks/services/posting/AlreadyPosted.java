package io.github.rkaushikdev.bushelworks.services.posting;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.ErrorCode;

/** Raised when a statement that is already in the journal is posted again. */
@ErrorCode("already_posted")
public class AlreadyPosted extends ConflictError {

    public AlreadyPosted(String message) {
        super(message);
    }
}
