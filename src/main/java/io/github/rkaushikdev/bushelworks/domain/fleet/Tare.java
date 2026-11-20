package io.github.rkaushikdev.bushelworks.domain.fleet;

import io.github.rkaushikdev.bushelworks.Decimals;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One empty weighing of a truck.
 *
 * <p>A tare is not a property of a truck. It is a property of a truck on a day: an empty hopper
 * weighs different amounts wet and dry, with and without a tarp, with and without the driver in the
 * cab. So a stored tare has a date on it and an age, and the scale house is told when it is stale.
 *
 * @param pounds what the empty truck weighed
 * @param weighedOn when
 * @param ticketNumber the ticket it was weighed on, if any
 */
public record Tare(BigDecimal pounds, LocalDate weighedOn, Optional<String> ticketNumber) {

    /** How long a stored tare is trusted, in days. Past this the truck is weighed empty again. */
    public static final int TARE_LIFE_DAYS = 30;

    /** The lightest plausible empty hopper bottom. Anything far outside is a keying mistake. */
    public static final BigDecimal PLAUSIBLE_LOW = new BigDecimal("18000");

    /** The heaviest plausible empty hopper bottom. */
    public static final BigDecimal PLAUSIBLE_HIGH = new BigDecimal("36000");

    public Tare {
        Objects.requireNonNull(weighedOn, "weighedOn");
        Objects.requireNonNull(ticketNumber, "ticketNumber");
        if (pounds.compareTo(PLAUSIBLE_LOW) < 0 || pounds.compareTo(PLAUSIBLE_HIGH) > 0) {
            throw new FleetError("a tare of " + pounds + " lb is outside " + PLAUSIBLE_LOW + " to " + PLAUSIBLE_HIGH);
        }
    }

    public LocalDate expiresOn() {
        return weighedOn.plusDays(TARE_LIFE_DAYS);
    }

    public boolean isCurrentOn(LocalDate day) {
        return !day.isBefore(weighedOn) && !day.isAfter(expiresOn());
    }

    public int ageDays(LocalDate on) {
        return (int) ChronoUnit.DAYS.between(weighedOn, on);
    }

    /** The average of several tares, to the pound, a tie going to the even pound. */
    public static Optional<BigDecimal> average(List<Tare> tares) {
        if (tares.isEmpty()) {
            return Optional.empty();
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Tare tare : tares) {
            total = total.add(tare.pounds());
        }
        return Optional.of(Decimals.divide(total, tares.size()).setScale(0, RoundingMode.HALF_EVEN));
    }
}
