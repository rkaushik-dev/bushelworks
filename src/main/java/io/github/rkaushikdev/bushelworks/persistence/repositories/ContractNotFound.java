package io.github.rkaushikdev.bushelworks.persistence.repositories;

import io.github.rkaushikdev.bushelworks.LookupFailure;

/** No purchase contract with that number. */
public class ContractNotFound extends LookupFailure {

    public ContractNotFound(String message) {
        super(message);
    }
}
