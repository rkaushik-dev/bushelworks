package io.github.rkaushikdev.bushelworks.services.shipping;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.LookupFailure;

/** Raised when a sale, or a shipment on one, is not on file. */
@ErrorCode("sale_not_found")
public class SaleNotFound extends LookupFailure {

    public SaleNotFound(String message) {
        super(message);
    }
}
