package io.github.rkaushikdev.bushelworks.domain.application;

import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a delivery cannot be applied as asked. */
public class ApplicationError extends ValidationError {

    public ApplicationError(String message) {
        super(message);
    }
}
