package io.github.rkaushikdev.bushelworks.reports.ach;

import io.github.rkaushikdev.bushelworks.Quoting;
import java.util.Objects;

/**
 * The house, as the bank knows it.
 *
 * @param name the house's name, as the bank has it
 * @param companyId the ten character id the bank gave the house
 * @param routingNumber the nine digit routing number of the house's own bank
 * @param bankName the name of that bank
 */
public record Originator(String name, String companyId, String routingNumber, String bankName) {

    public Originator {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(routingNumber, "routingNumber");
        Objects.requireNonNull(bankName, "bankName");
        if (!Ach.isNineDigits(routingNumber)) {
            throw new AchError(Quoting.quoted(routingNumber) + " is not a routing number");
        }
        if (companyId.codePointCount(0, companyId.length()) != 10) {
            throw new AchError("a company id is ten characters");
        }
    }
}
