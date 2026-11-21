package io.github.rkaushikdev.bushelworks.services.settlement;

import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.services.intake.GradedLoad;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * One load as it appears inside a producer settlement.
 *
 * @param ticketId the stored ticket's key
 * @param ticketNumber the ticket's number
 * @param load the load as intake graded it
 * @param unitPrice what it is paid per settled bushel
 */
public record SettledTicket(Integer ticketId, String ticketNumber, GradedLoad load, BigDecimal unitPrice) {

    public SettledTicket {
        Objects.requireNonNull(ticketId, "ticketId");
        Objects.requireNonNull(ticketNumber, "ticketNumber");
        Objects.requireNonNull(load, "load");
        Objects.requireNonNull(unitPrice, "unitPrice");
    }

    /** The settled bushels at the unit price, to the cent. */
    public BigDecimal gross() {
        return Money.extend(load.settledBushels(), unitPrice);
    }
}
