package io.github.rkaushikdev.bushelworks.api.schemas;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * One line of a settlement statement.
 *
 * @param kind gross, deduction, advance and the like
 * @param code the line's code
 * @param description what the line says
 * @param dollars the money
 * @param quantity the bushels or units the line was worked out on, when it was
 * @param rate the rate it was worked out at, when it was
 */
public record SettlementLineOut(
        String kind,
        String code,
        String description,
        BigDecimal dollars,
        Optional<BigDecimal> quantity,
        Optional<BigDecimal> rate) {

    public SettlementLineOut {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(dollars, "dollars");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(rate, "rate");
    }

    /** A line worked out on nothing: a flat charge or credit. */
    public static SettlementLineOut flat(String kind, String code, String description, BigDecimal dollars) {
        return new SettlementLineOut(kind, code, description, dollars, Optional.empty(), Optional.empty());
    }
}
