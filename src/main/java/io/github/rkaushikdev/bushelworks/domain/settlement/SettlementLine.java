package io.github.rkaushikdev.bushelworks.domain.settlement;

import io.github.rkaushikdev.bushelworks.Money;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * One row on the statement, stored as a positive magnitude and a kind that decides the sign.
 *
 * @param kind what the line is
 * @param code the line's code
 * @param description what the statement prints
 * @param dollars the magnitude, never negative
 * @param quantity the bushels or points it was worked on, when there were any
 * @param rate the rate it was worked at, when there was one
 */
public record SettlementLine(
        LineKind kind,
        String code,
        String description,
        BigDecimal dollars,
        Optional<BigDecimal> quantity,
        Optional<BigDecimal> rate) {

    public SettlementLine {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(rate, "rate");
        if (dollars.signum() < 0) {
            throw new SettlementError(code + ": store a line as a positive amount and let the kind decide the sign");
        }
    }

    /** A line with no quantity and no rate. */
    public SettlementLine(LineKind kind, String code, String description, BigDecimal dollars) {
        this(kind, code, description, dollars, Optional.empty(), Optional.empty());
    }

    /** The amount as it contributes to the net. */
    public BigDecimal signed() {
        return kind.reducing() ? Money.amount(dollars.negate()) : Money.amount(dollars);
    }
}
