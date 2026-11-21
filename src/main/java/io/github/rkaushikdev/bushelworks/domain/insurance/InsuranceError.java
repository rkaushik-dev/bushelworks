package io.github.rkaushikdev.bushelworks.domain.insurance;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a production report cannot be assembled. */
@ErrorCode("insurance_error")
public class InsuranceError extends ValidationError {

    public InsuranceError(String message) {
        super(message);
    }
}
