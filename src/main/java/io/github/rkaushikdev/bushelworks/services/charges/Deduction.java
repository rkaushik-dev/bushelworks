package io.github.rkaushikdev.bushelworks.services.charges;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Anything else the house takes off, with the reason on the statement.
 *
 * <p>Freight the house paid on the producer's behalf, a lien assignment, a correction from a
 * previous settlement. Always positive; the statement's line kind decides the sign.
 *
 * @param code the line code
 * @param description the reason the statement prints
 * @param dollars the amount, always positive
 */
public record Deduction(String code, String description, BigDecimal dollars) {

    public Deduction {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(description, "description");
        if (dollars.signum() <= 0) {
            throw new SettlementRefused(code + ": a deduction has to be positive");
        }
        if (description.isEmpty()) {
            throw new SettlementRefused(code + ": a deduction needs a description");
        }
    }
}
