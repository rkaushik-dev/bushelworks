package io.github.rkaushikdev.bushelworks.domain.complaints;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a complaint cannot be recorded or closed. */
@ErrorCode("complaint_error")
public class ComplaintError extends ValidationError {

    public ComplaintError(String message) {
        super(message);
    }
}
