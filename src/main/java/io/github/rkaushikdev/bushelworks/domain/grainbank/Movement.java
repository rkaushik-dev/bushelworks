package io.github.rkaushikdev.bushelworks.domain.grainbank;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * One deposit or withdrawal on a feed account. A withdrawal carries negative bushels.
 *
 * @param reference the ticket or feed order
 * @param on when
 * @param bushels bushels in, or out when negative
 * @param tons tons of feed made, for a withdrawal
 * @param rationCode the ration made, for a withdrawal
 * @param charge the processing charge
 */
public record Movement(
        String reference,
        LocalDate on,
        BigDecimal bushels,
        Optional<BigDecimal> tons,
        Optional<String> rationCode,
        BigDecimal charge) {

    public Movement {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(on, "on");
        Objects.requireNonNull(tons, "tons");
        Objects.requireNonNull(rationCode, "rationCode");
        Objects.requireNonNull(charge, "charge");
        if (bushels.signum() == 0) {
            throw new GrainBankError(reference + ": a movement of nothing");
        }
        if (bushels.signum() < 0 && tons.isEmpty()) {
            throw new GrainBankError(reference + ": a withdrawal is measured in tons");
        }
    }

    /** Grain hauled in. */
    public static Movement deposit(String reference, LocalDate on, BigDecimal bushels) {
        return new Movement(reference, on, bushels, Optional.empty(), Optional.empty(), new BigDecimal("0.00"));
    }

    public boolean isDeposit() {
        return bushels.signum() > 0;
    }
}
