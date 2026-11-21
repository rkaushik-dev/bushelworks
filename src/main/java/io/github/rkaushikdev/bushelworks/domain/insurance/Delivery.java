package io.github.rkaushikdev.bushelworks.domain.insurance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One ticket, as the adjuster wants to see it: bushels as settled, after shrink and before
 * discounts, because discounts are a price adjustment and the adjuster does not want them.
 *
 * @param ticketNumber the ticket
 * @param farmCode the farm it came from
 * @param scaleDate when
 * @param bushels settled bushels
 */
public record Delivery(String ticketNumber, String farmCode, LocalDate scaleDate, BigDecimal bushels) {

    public Delivery {
        Objects.requireNonNull(ticketNumber, "ticketNumber");
        Objects.requireNonNull(farmCode, "farmCode");
        Objects.requireNonNull(scaleDate, "scaleDate");
        if (bushels.signum() < 0) {
            throw new InsuranceError(ticketNumber + ": negative bushels");
        }
    }
}
