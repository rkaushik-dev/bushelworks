package io.github.rkaushikdev.bushelworks.reports.remittance;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a checkoff return cannot be assembled. */
@ErrorCode("remittance_error")
public class RemittanceError extends ValidationError {

    public RemittanceError(String message) {
        super(message);
    }
}
