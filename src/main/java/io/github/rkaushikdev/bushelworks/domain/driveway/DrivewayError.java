package io.github.rkaushikdev.bushelworks.domain.driveway;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a truck cannot move where it is asked to. */
@ErrorCode("driveway_error")
public class DrivewayError extends ValidationError {

    public DrivewayError(String message) {
        super(message);
    }
}
