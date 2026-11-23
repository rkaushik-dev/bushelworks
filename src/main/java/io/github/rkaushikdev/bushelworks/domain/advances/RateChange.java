package io.github.rkaushikdev.bushelworks.domain.advances;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A new interest rate on a crop advance from a day on.
 *
 * <p>A crop line written at prime plus one moves every time the bank moves prime, and the note
 * says the new rate runs from the day it was announced. The rate before the first change is the
 * one the advance was written at.
 *
 * @param effectiveOn the first day the new rate is charged
 * @param annualRatePercent the new rate, simple, a year
 */
public record RateChange(LocalDate effectiveOn, BigDecimal annualRatePercent) {

    public RateChange {
        Objects.requireNonNull(effectiveOn, "effectiveOn");
        Objects.requireNonNull(annualRatePercent, "annualRatePercent");
        if (annualRatePercent.signum() < 0) {
            throw new AdvanceError("a rate cannot be negative from " + effectiveOn);
        }
    }
}
