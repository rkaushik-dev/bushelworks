package io.github.rkaushikdev.bushelworks.services.payments;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * One cheque covering one or more settlements.
 *
 * @param chequeNumber the number off the cheque stock
 * @param partyCode who it pays
 * @param paidOn the day it was written
 * @param dollars what it is for
 * @param settlementNumbers the statements it pays, oldest first
 */
public record Cheque(
        String chequeNumber, String partyCode, LocalDate paidOn, BigDecimal dollars, List<String> settlementNumbers) {

    public Cheque {
        Objects.requireNonNull(chequeNumber, "chequeNumber");
        Objects.requireNonNull(partyCode, "partyCode");
        Objects.requireNonNull(paidOn, "paidOn");
        Objects.requireNonNull(dollars, "dollars");
        settlementNumbers = List.copyOf(settlementNumbers);
    }

    /** Whether it pays more than one statement. */
    public boolean isMultiple() {
        return settlementNumbers.size() > 1;
    }
}
