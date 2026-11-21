package io.github.rkaushikdev.bushelworks.domain.sales;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a sale cannot stand as written. */
@ErrorCode("sale_error")
public class SaleError extends ValidationError {

    public SaleError(String message) {
        super(message);
    }
}
