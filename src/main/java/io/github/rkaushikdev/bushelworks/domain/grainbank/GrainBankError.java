package io.github.rkaushikdev.bushelworks.domain.grainbank;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a feed account cannot do what is asked. */
@ErrorCode("grain_bank_error")
public class GrainBankError extends ValidationError {

    public GrainBankError(String message) {
        super(message);
    }
}
