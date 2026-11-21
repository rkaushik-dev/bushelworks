package io.github.rkaushikdev.bushelworks.services.payments;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a cheque cannot be written, or voided, as asked. */
@ErrorCode("payment_error")
public class PaymentError extends ValidationError {

    public PaymentError(String message) {
        super(message);
    }
}
