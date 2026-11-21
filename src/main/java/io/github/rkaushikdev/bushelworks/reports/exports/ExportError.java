package io.github.rkaushikdev.bushelworks.reports.exports;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when something cannot be written out, or an export does not read back. */
@ErrorCode("export_error")
public class ExportError extends ValidationError {

    public ExportError(String message) {
        super(message);
    }
}
