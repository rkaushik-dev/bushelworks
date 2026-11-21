package io.github.rkaushikdev.bushelworks.reports.shrinkage;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a shrink reconciliation cannot be made. */
@ErrorCode("shrinkage_error")
public class ShrinkageError extends ValidationError {

    public ShrinkageError(String message) {
        super(message);
    }
}
