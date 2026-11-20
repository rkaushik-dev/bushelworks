package io.github.rkaushikdev.bushelworks.domain.freight;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a haul cannot be rated. */
@ErrorCode("freight_error")
public class FreightError extends ValidationError {

    public FreightError(String message) {
        super(message);
    }
}
