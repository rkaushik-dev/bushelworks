package io.github.rkaushikdev.bushelworks.domain.dryer;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a run cannot be logged or costed. */
@ErrorCode("dryer_error")
public class DryerError extends ValidationError {

    public DryerError(String message) {
        super(message);
    }
}
