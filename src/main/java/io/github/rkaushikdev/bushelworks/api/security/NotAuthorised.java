package io.github.rkaushikdev.bushelworks.api.security;

import io.github.rkaushikdev.bushelworks.BushelworksError;
import io.github.rkaushikdev.bushelworks.ErrorCode;

/** No usable key: none sent to a house that wants one, or one that is not on file. Answered with a 401. */
@ErrorCode("not_authorised")
public class NotAuthorised extends BushelworksError {

    public NotAuthorised(String message) {
        super(message);
    }
}
