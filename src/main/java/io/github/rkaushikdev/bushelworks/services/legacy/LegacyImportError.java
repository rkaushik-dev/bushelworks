package io.github.rkaushikdev.bushelworks.services.legacy;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a row of the old ticket file cannot be read at all. */
@ErrorCode("import_error")
public class LegacyImportError extends ValidationError {

    public LegacyImportError(String message) {
        super(message);
    }
}
