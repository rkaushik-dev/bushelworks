package io.github.rkaushikdev.bushelworks.domain.position;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a position cannot be assembled or does not reconcile. */
@ErrorCode("position_error")
public class PositionError extends ValidationError {

    public PositionError(String message) {
        super(message);
    }
}
