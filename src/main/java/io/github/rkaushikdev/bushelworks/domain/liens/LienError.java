package io.github.rkaushikdev.bushelworks.domain.liens;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when an interest cannot stand as filed. */
@ErrorCode("lien_error")
public class LienError extends ValidationError {

    public LienError(String message) {
        super(message);
    }
}
