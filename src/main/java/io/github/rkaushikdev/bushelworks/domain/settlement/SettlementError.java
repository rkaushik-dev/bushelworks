package io.github.rkaushikdev.bushelworks.domain.settlement;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a settlement cannot be assembled or does not balance. */
@ErrorCode("settlement_error")
public class SettlementError extends ValidationError {

    public SettlementError(String message) {
        super(message);
    }
}
