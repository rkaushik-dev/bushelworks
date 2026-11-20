package io.github.rkaushikdev.bushelworks.domain.advances;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when an advance cannot be drawn or recovered as asked. */
@ErrorCode("advance_error")
public class AdvanceError extends ValidationError {

    public AdvanceError(String message) {
        super(message);
    }
}
