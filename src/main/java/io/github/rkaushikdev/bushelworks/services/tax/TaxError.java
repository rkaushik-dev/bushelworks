package io.github.rkaushikdev.bushelworks.services.tax;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a reporting figure cannot be worked out. */
@ErrorCode("tax_error")
public class TaxError extends ValidationError {

    public TaxError(String message) {
        super(message);
    }
}
