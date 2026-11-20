package io.github.rkaushikdev.bushelworks.domain.blending;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a blend cannot be worked out. */
@ErrorCode("blend_error")
public class BlendError extends ValidationError {

    public BlendError(String message) {
        super(message);
    }

    public BlendError(String message, Throwable cause) {
        super(message, cause);
    }
}
