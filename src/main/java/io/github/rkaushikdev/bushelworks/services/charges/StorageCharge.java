package io.github.rkaushikdev.bushelworks.services.charges;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A storage bill already worked out by the warehousing service, ready to go onto a statement.
 *
 * @param lotId the storage lot billed
 * @param dollars the bill, never negative
 * @param through the last day billed
 */
public record StorageCharge(String lotId, BigDecimal dollars, LocalDate through) {

    public StorageCharge {
        Objects.requireNonNull(lotId, "lotId");
        Objects.requireNonNull(through, "through");
        if (dollars.signum() < 0) {
            throw new SettlementRefused(lotId + ": a storage charge cannot be negative");
        }
    }
}
