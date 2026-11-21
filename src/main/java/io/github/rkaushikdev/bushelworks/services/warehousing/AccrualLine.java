package io.github.rkaushikdev.bushelworks.services.warehousing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * What one lot accrued between two days.
 *
 * @param lotId the lot
 * @param partyCode whose grain it is
 * @param commodityCode what it is
 * @param openBushels what was still in it
 * @param fromDay the day it was last billed to; empty for a lot never billed, charged from receipt
 * @param through the day it is billed to now
 * @param dollars the storage charged for the span
 */
public record AccrualLine(
        String lotId,
        String partyCode,
        String commodityCode,
        BigDecimal openBushels,
        Optional<LocalDate> fromDay,
        LocalDate through,
        BigDecimal dollars) {

    public AccrualLine {
        Objects.requireNonNull(lotId, "lotId");
        Objects.requireNonNull(partyCode, "partyCode");
        Objects.requireNonNull(commodityCode, "commodityCode");
        Objects.requireNonNull(openBushels, "openBushels");
        Objects.requireNonNull(fromDay, "fromDay");
        Objects.requireNonNull(through, "through");
        Objects.requireNonNull(dollars, "dollars");
    }
}
