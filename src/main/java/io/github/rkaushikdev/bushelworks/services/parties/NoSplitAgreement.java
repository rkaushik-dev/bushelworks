package io.github.rkaushikdev.bushelworks.services.parties;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.LookupFailure;

/** No split agreement is in force for the farm on the day asked about. */
@ErrorCode("no_split_agreement")
public class NoSplitAgreement extends LookupFailure {

    public NoSplitAgreement(String message) {
        super(message);
    }
}
