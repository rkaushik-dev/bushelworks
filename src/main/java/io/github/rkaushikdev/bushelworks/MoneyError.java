package io.github.rkaushikdev.bushelworks;

/** Raised for a money value that cannot be represented or allocated. */
public class MoneyError extends ValidationError {

    public MoneyError(String message) {
        super(message);
    }
}
