package io.github.rkaushikdev.bushelworks;

/** The operation is legal but the current state will not take it. */
@ErrorCode("conflict")
public class ConflictError extends BushelworksError {

    public ConflictError(String message) {
        super(message);
    }

    public ConflictError(String message, String code) {
        super(message, code);
    }

    public ConflictError(String message, Throwable cause) {
        super(message, cause);
    }
}
