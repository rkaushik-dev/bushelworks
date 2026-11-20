package io.github.rkaushikdev.bushelworks.domain.inspection;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a certificate cannot stand as recorded. */
@ErrorCode("inspection_error")
public class InspectionError extends ValidationError {

    public InspectionError(String message) {
        super(message);
    }
}
