package io.github.rkaushikdev.bushelworks.services.monthend;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a month cannot be closed, filed or opened again as asked. */
@ErrorCode("month_end_error")
public class MonthEndError extends ValidationError {

    public MonthEndError(String message) {
        super(message);
    }
}
