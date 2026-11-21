package io.github.rkaushikdev.bushelworks.reports.fulfilment;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a contract cannot be assessed. */
@ErrorCode("fulfilment_error")
public class FulfilmentError extends ValidationError {

    public FulfilmentError(String message) {
        super(message);
    }
}
