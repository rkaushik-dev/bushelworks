package io.github.rkaushikdev.bushelworks.services.board;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.LookupFailure;

/** Nothing has been posted for the slot asked about. */
@ErrorCode("no_bid")
public class NoBid extends LookupFailure {

    public NoBid(String message) {
        super(message);
    }
}
