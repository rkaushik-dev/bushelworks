package io.github.rkaushikdev.bushelworks.services.charges;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a settlement cannot be produced from what is on hand. */
@ErrorCode("settlement_refused")
public class SettlementRefused extends ValidationError {

    public SettlementRefused(String message) {
        super(message);
    }
}
