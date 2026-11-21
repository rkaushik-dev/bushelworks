package io.github.rkaushikdev.bushelworks.services.settlement;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a settlement run finds no tickets it can pay for. */
@ErrorCode("nothing_to_settle")
public class NothingToSettle extends ValidationError {

    public NothingToSettle(String message) {
        super(message);
    }
}
