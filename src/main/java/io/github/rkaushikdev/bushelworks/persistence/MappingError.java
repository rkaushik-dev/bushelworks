package io.github.rkaushikdev.bushelworks.persistence;

import io.github.rkaushikdev.bushelworks.ValidationError;

/** A stored row cannot be read back as a domain object. */
public class MappingError extends ValidationError {

    public MappingError(String message) {
        super(message);
    }

    public MappingError(String message, Throwable cause) {
        super(message, cause);
    }
}
