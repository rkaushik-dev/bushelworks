package io.github.rkaushikdev.bushelworks.reports.patronage;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a year of business cannot be ranked. */
@ErrorCode("patronage_error")
public class PatronageError extends ValidationError {

    public PatronageError(String message) {
        super(message);
    }
}
