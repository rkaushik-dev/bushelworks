package io.github.rkaushikdev.bushelworks;

/** Something referred to is not on file. */
@ErrorCode("not_found")
public class LookupFailure extends BushelworksError {

    public LookupFailure(String message) {
        super(message);
    }

    public LookupFailure(String message, String code) {
        super(message, code);
    }

    public LookupFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
