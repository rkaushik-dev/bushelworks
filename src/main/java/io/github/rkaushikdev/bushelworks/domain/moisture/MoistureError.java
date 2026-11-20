package io.github.rkaushikdev.bushelworks.domain.moisture;

import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a moisture reading cannot be shrunk as configured. */
public class MoistureError extends ValidationError {

    public MoistureError(String message) {
        super(message);
    }
}
