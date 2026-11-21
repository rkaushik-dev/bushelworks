package io.github.rkaushikdev.bushelworks.services.settlement;

import io.github.rkaushikdev.bushelworks.persistence.models.ApplicationRow;
import io.github.rkaushikdev.bushelworks.persistence.models.ContractRow;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Bushels off a ticket that are still applied to a contract, with the contract they landed on.
 *
 * @param application the stored application, not reversed
 * @param contract the contract it fills
 */
public record LiveApplication(ApplicationRow application, ContractRow contract) {

    public LiveApplication {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(contract, "contract");
    }

    /** The bushels applied. */
    public BigDecimal bushels() {
        return application.getBushels();
    }
}
