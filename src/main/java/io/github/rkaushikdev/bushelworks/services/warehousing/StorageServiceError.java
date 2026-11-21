package io.github.rkaushikdev.bushelworks.services.warehousing;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when grain cannot go into storage, or come out of it, as asked. */
@ErrorCode("storage_service_error")
public class StorageServiceError extends ValidationError {

    public StorageServiceError(String message) {
        super(message);
    }
}
