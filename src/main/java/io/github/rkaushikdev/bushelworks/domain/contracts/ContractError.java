package io.github.rkaushikdev.bushelworks.domain.contracts;

import io.github.rkaushikdev.bushelworks.ValidationError;

/** Raised when a contract cannot stand as written or as amended. */
public class ContractError extends ValidationError {

    public ContractError(String message) {
        super(message);
    }
}
