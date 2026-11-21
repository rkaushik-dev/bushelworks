package io.github.rkaushikdev.bushelworks.services.dayend;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when the day will not close. */
@ErrorCode("close_error")
public class CloseError extends ValidationError {

    public CloseError(String message) {
        super(message);
    }
}
