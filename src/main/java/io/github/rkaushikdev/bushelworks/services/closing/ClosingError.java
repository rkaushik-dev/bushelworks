package io.github.rkaushikdev.bushelworks.services.closing;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when the year will not close. */
@ErrorCode("closing_error")
public class ClosingError extends ValidationError {

    public ClosingError(String message) {
        super(message);
    }
}
