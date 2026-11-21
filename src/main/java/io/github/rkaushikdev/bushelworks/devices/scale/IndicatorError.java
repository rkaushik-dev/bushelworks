package io.github.rkaushikdev.bushelworks.devices.scale;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a frame off the scale indicator cannot be read as a weight. */
@ErrorCode("indicator_error")
public class IndicatorError extends ValidationError {

    public IndicatorError(String message) {
        super(message);
    }
}
