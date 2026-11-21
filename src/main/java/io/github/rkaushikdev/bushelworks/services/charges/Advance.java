package io.github.rkaushikdev.bushelworks.services.charges;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Money already paid against grain not yet settled, recovered on the statement that settles it.
 *
 * @param reference the advance's reference
 * @param dollars what was paid, always positive
 * @param advancedOn the day it was paid
 */
public record Advance(String reference, BigDecimal dollars, LocalDate advancedOn) {

    public Advance {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(advancedOn, "advancedOn");
        if (dollars.signum() <= 0) {
            throw new SettlementRefused(reference + ": an advance has to be positive");
        }
        if (reference.isEmpty()) {
            throw new SettlementRefused("an advance needs a reference");
        }
    }
}
