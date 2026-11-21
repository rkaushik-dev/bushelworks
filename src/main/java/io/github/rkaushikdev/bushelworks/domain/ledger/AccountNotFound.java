package io.github.rkaushikdev.bushelworks.domain.ledger;

import io.github.rkaushikdev.bushelworks.ErrorCode;
import io.github.rkaushikdev.bushelworks.LookupFailure;

/** An account code that is not in the chart, or not on the trial balance asked about. */
@ErrorCode("account_not_found")
public class AccountNotFound extends LookupFailure {

    public AccountNotFound(String message) {
        super(message);
    }
}
