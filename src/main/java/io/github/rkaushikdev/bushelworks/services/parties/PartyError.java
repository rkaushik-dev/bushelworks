package io.github.rkaushikdev.bushelworks.services.parties;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when the producer file cannot be changed as asked. */
@ErrorCode("party_error")
public class PartyError extends ValidationError {

    public PartyError(String message) {
        super(message);
    }
}
