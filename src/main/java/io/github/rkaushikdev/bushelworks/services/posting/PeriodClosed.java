package io.github.rkaushikdev.bushelworks.services.posting;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.ErrorCode;

/** Raised when something tries to post into a month whose return has been filed. */
@ErrorCode("period_closed")
public class PeriodClosed extends ConflictError {

    public PeriodClosed(String message) {
        super(message);
    }
}
