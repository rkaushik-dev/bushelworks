package io.github.rkaushikdev.bushelworks.services.deferred;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a price later arrangement cannot be worked as asked. */
@ErrorCode("deferred_price_error")
public class DeferredPriceError extends ValidationError {

    public DeferredPriceError(String message) {
        super(message);
    }
}
