package io.github.rkaushikdev.bushelworks.domain.contracts;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * The span the producer may deliver against, both days included.
 *
 * @param opens the first day of delivery
 * @param closes the last day of delivery
 */
public record DeliveryWindow(LocalDate opens, LocalDate closes) {

    public DeliveryWindow {
        Objects.requireNonNull(opens, "opens");
        Objects.requireNonNull(closes, "closes");
        if (closes.isBefore(opens)) {
            throw new ContractError("a delivery window has to close after it opens");
        }
    }

    public boolean contains(LocalDate day) {
        return !day.isBefore(opens) && !day.isAfter(closes);
    }

    /** How many days the window covers, both ends counted. */
    public int days() {
        return (int) ChronoUnit.DAYS.between(opens, closes) + 1;
    }
}
