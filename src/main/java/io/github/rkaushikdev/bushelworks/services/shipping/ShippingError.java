package io.github.rkaushikdev.bushelworks.services.shipping;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a load cannot go out, or come back, as asked. */
@ErrorCode("shipping_error")
public class ShippingError extends ValidationError {

    public ShippingError(String message) {
        super(message);
    }
}
