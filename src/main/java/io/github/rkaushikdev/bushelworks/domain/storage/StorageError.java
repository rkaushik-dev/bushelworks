package io.github.rkaushikdev.bushelworks.domain.storage;

import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a storage charge cannot be worked out as configured. */
public class StorageError extends ValidationError {

    public StorageError(String message) {
        super(message);
    }
}
