package io.github.rkaushikdev.bushelworks.services.corrections;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a correction cannot be made as asked. */
@ErrorCode("correction_error")
public class CorrectionError extends ValidationError {

    public CorrectionError(String message) {
        super(message);
    }
}
