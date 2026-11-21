package io.github.rkaushikdev.bushelworks.services.binyard;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when grain cannot go where it is asked to, or come out as asked. */
@ErrorCode("bin_yard_error")
public class BinYardError extends ValidationError {

    public BinYardError(String message) {
        super(message);
    }
}
