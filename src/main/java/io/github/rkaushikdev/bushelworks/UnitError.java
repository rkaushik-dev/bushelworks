package io.github.rkaushikdev.bushelworks;

/** Raised when a conversion is asked for something it cannot express. */
public class UnitError extends ValidationError {

    public UnitError(String message) {
        super(message);
    }
}
