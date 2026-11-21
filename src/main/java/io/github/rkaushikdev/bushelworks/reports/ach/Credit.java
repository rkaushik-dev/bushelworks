package io.github.rkaushikdev.bushelworks.reports.ach;

import io.github.rkaushikdev.bushelworks.Quoting;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * One payment to one producer.
 *
 * @param partyCode the producer
 * @param name the producer's name, as it goes to the bank
 * @param routingNumber the nine digit routing number of the producer's bank
 * @param accountNumber the producer's account at that bank
 * @param dollars what is paid, more than nothing
 * @param reference what it pays, a settlement number
 */
public record Credit(
        String partyCode,
        String name,
        String routingNumber,
        String accountNumber,
        BigDecimal dollars,
        String reference) {

    public Credit {
        Objects.requireNonNull(partyCode, "partyCode");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(routingNumber, "routingNumber");
        Objects.requireNonNull(accountNumber, "accountNumber");
        Objects.requireNonNull(dollars, "dollars");
        Objects.requireNonNull(reference, "reference");
        if (dollars.signum() <= 0) {
            throw new AchError(partyCode + ": an ACH credit has to be positive");
        }
        if (!Ach.isNineDigits(routingNumber)) {
            throw new AchError(partyCode + ": " + Quoting.quoted(routingNumber) + " is not a routing number");
        }
        if (accountNumber.isEmpty()) {
            throw new AchError(partyCode + ": no account number");
        }
    }

    /** The amount in whole cents, a half cent going to the even cent. */
    public long cents() {
        return dollars.movePointRight(2).setScale(0, RoundingMode.HALF_EVEN).longValueExact();
    }
}
