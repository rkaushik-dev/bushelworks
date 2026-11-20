package io.github.rkaushikdev.bushelworks.domain.hedging;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when the futures account cannot do what is asked. */
@ErrorCode("hedge_error")
public class HedgeError extends ValidationError {

    public HedgeError(String message) {
        super(message);
    }
}
