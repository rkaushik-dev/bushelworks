package io.github.rkaushikdev.bushelworks.domain.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * One slice of a ticket landing on one contract, or on no contract at all.
 *
 * @param ticketNumber the ticket the bushels came in on
 * @param contractNumber the contract they fill, or nothing for an overage
 * @param bushels how many
 * @param appliedOn the day they were applied
 * @param disposition {@code contract}, or for an overage {@code spot} or {@code store}
 */
public record Application(
        String ticketNumber,
        Optional<String> contractNumber,
        BigDecimal bushels,
        LocalDate appliedOn,
        String disposition) {

    public Application {
        Objects.requireNonNull(ticketNumber, "ticketNumber");
        Objects.requireNonNull(contractNumber, "contractNumber");
        Objects.requireNonNull(appliedOn, "appliedOn");
        Objects.requireNonNull(disposition, "disposition");
        if (bushels.signum() <= 0) {
            throw new ApplicationError(ticketNumber + ": cannot apply " + bushels + " bushels");
        }
    }

    /** Bushels landing on a contract. */
    public static Application onContract(String ticketNumber, String contractNumber, BigDecimal bushels, LocalDate on) {
        return new Application(ticketNumber, Optional.of(contractNumber), bushels, on, "contract");
    }
}
