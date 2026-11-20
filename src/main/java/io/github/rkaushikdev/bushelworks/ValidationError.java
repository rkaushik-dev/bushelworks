package io.github.rkaushikdev.bushelworks;

/** Something handed in does not hold together. */
@ErrorCode("validation_error")
public class ValidationError extends BushelworksError {

    public ValidationError(String message) {
        super(message);
    }

    public ValidationError(String message, String code) {
        super(message, code);
    }

    public ValidationError(String message, Throwable cause) {
        super(message, cause);
    }
}
