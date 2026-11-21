package io.github.rkaushikdev.bushelworks.devices.moisture;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a moisture meter report cannot be read, or was taken on the wrong calibration. */
@ErrorCode("meter_error")
public class MeterError extends ValidationError {

    public MeterError(String message) {
        super(message);
    }
}
