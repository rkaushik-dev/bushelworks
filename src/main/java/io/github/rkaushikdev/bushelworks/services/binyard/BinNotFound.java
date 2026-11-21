package io.github.rkaushikdev.bushelworks.services.binyard;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.LookupFailure;

/** No bin by that id at that site. */
@ErrorCode("bin_not_found")
public class BinNotFound extends LookupFailure {

    public BinNotFound(String message) {
        super(message);
    }
}
