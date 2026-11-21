package io.github.rkaushikdev.bushelworks.reports.spool;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a document cannot be spooled. */
@ErrorCode("spool_error")
public class SpoolError extends ValidationError {

    public SpoolError(String message) {
        super(message);
    }
}
