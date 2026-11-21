package io.github.rkaushikdev.bushelworks.services.warehousing;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.LookupFailure;

/** Raised when a storage lot is not on file. */
@ErrorCode("lot_not_found")
public class LotNotFound extends LookupFailure {

    public LotNotFound(String message) {
        super(message);
    }
}
