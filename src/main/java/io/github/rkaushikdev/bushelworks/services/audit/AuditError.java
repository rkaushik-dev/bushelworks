package io.github.rkaushikdev.bushelworks.services.audit;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when an audit entry cannot be written as asked. */
@ErrorCode("audit_error")
public class AuditError extends ValidationError {

    public AuditError(String message) {
        super(message);
    }
}
