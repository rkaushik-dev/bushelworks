package io.github.rkaushikdev.bushelworks.services.intake;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a ticket cannot be taken in as written. */
@ErrorCode("intake_error")
public class IntakeError extends ValidationError {

    public IntakeError(String message) {
        super(message);
    }
}
