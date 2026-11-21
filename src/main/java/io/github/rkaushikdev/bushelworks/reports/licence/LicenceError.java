package io.github.rkaushikdev.bushelworks.reports.licence;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a warehouse return cannot be assembled. */
@ErrorCode("licence_error")
public class LicenceError extends ValidationError {

    public LicenceError(String message) {
        super(message);
    }
}
