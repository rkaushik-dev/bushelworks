package io.github.rkaushikdev.bushelworks.services.numbering;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a document number cannot be allocated, or a string is not one. */
@ErrorCode("numbering_error")
public class NumberingError extends ValidationError {

    public NumberingError(String message) {
        super(message);
    }
}
