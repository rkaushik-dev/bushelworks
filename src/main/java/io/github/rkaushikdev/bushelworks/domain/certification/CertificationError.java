package io.github.rkaushikdev.bushelworks.domain.certification;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a test or a certificate cannot stand. */
@ErrorCode("certification_error")
public class CertificationError extends ValidationError {

    public CertificationError(String message) {
        super(message);
    }
}
