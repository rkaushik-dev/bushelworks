package io.github.rkaushikdev.bushelworks.services.deferred;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * What a producer has unpriced in one commodity, and what it has cost him so far.
 *
 * @param partyCode the producer
 * @param commodityCode the commodity
 * @param bushels what is still unpriced
 * @param oldestDelivery the day the oldest open lot came in
 * @param serviceOwed the service fee on all of it to the day asked about
 * @param overdueLots the lots past the term the state allows, oldest first
 */
public record DeferredPosition(
        String partyCode,
        String commodityCode,
        BigDecimal bushels,
        LocalDate oldestDelivery,
        BigDecimal serviceOwed,
        List<String> overdueLots) {

    public DeferredPosition {
        Objects.requireNonNull(partyCode, "partyCode");
        Objects.requireNonNull(commodityCode, "commodityCode");
        Objects.requireNonNull(bushels, "bushels");
        Objects.requireNonNull(oldestDelivery, "oldestDelivery");
        Objects.requireNonNull(serviceOwed, "serviceOwed");
        overdueLots = List.copyOf(overdueLots);
    }

    /** Whether any of it has run past the term. */
    public boolean isOverdue() {
        return !overdueLots.isEmpty();
    }
}
