package io.github.rkaushikdev.bushelworks.services.corrections;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Bushels taken back off a contract.
 *
 * @param ticketNumber the ticket they came in on, which is not touched
 * @param contractNumber the contract they came off
 * @param bushels how many
 * @param reversedOn the day they came off
 */
public record Unapplied(String ticketNumber, String contractNumber, BigDecimal bushels, LocalDate reversedOn) {

    public Unapplied {
        Objects.requireNonNull(ticketNumber, "ticketNumber");
        Objects.requireNonNull(contractNumber, "contractNumber");
        Objects.requireNonNull(bushels, "bushels");
        Objects.requireNonNull(reversedOn, "reversedOn");
    }
}
