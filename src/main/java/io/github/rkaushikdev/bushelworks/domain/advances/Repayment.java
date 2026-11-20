package io.github.rkaushikdev.bushelworks.domain.advances;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One recovery against an advance.
 *
 * @param settlementNumber the settlement it came out of
 * @param dollars how much
 * @param repaidOn when
 */
public record Repayment(String settlementNumber, BigDecimal dollars, LocalDate repaidOn) {

    public Repayment {
        Objects.requireNonNull(settlementNumber, "settlementNumber");
        Objects.requireNonNull(repaidOn, "repaidOn");
        if (dollars.signum() <= 0) {
            throw new AdvanceError(settlementNumber + ": a repayment has to be positive");
        }
    }
}
