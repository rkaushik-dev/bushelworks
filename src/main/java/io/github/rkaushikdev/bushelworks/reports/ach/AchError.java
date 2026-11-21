package io.github.rkaushikdev.bushelworks.reports.ach;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a payment file cannot be built to the bank's format. */
@ErrorCode("ach_error")
public class AchError extends ValidationError {

    public AchError(String message) {
        super(message);
    }
}
