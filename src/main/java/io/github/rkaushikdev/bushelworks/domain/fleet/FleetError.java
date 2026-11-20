package io.github.rkaushikdev.bushelworks.domain.fleet;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a truck or a tare cannot stand as recorded. */
@ErrorCode("fleet_error")
public class FleetError extends ValidationError {

    public FleetError(String message) {
        super(message);
    }
}
