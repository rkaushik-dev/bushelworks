package io.github.rkaushikdev.bushelworks.domain.bins;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a bin cannot take or give what is asked. */
@ErrorCode("bin_error")
public class BinError extends ValidationError {

    public BinError(String message) {
        super(message);
    }
}
