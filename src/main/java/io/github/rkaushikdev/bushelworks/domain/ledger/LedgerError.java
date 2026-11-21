package io.github.rkaushikdev.bushelworks.domain.ledger;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when the books will not take what they were handed. */
@ErrorCode("ledger_error")
public class LedgerError extends ValidationError {

    public LedgerError(String message) {
        super(message);
    }
}
