package io.github.rkaushikdev.bushelworks.reports.yearend;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a year cannot be summarised. */
@ErrorCode("year_end_error")
public class YearEndError extends ValidationError {

    public YearEndError(String message) {
        super(message);
    }
}
