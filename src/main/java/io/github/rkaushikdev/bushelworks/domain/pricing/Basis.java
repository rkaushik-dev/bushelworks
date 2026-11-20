package io.github.rkaushikdev.bushelworks.domain.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A basis quote against a named month, in dollars per bushel.
 *
 * @param month the futures month the basis is quoted against
 * @param value dollars per bushel over or under the futures
 * @param quotedOn the day of the quote
 * @param locationCode the location it is good at, or {@code *} for every location
 */
public record Basis(FuturesMonth month, BigDecimal value, LocalDate quotedOn, String locationCode) {

    private static final BigDecimal PLAUSIBLE = new BigDecimal("5");

    public Basis {
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(quotedOn, "quotedOn");
        Objects.requireNonNull(locationCode, "locationCode");
        if (value.abs().compareTo(PLAUSIBLE) > 0) {
            throw new PricingError("basis of " + value + " is out of any plausible range");
        }
    }

    /** A quote good at every location. */
    public Basis(FuturesMonth month, BigDecimal value, LocalDate quotedOn) {
        this(month, value, quotedOn, "*");
    }
}
