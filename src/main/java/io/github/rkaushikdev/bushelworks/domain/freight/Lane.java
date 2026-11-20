package io.github.rkaushikdev.bushelworks.domain.freight;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One origin to one destination, with the distance the carrier is paid on.
 *
 * @param origin where the load starts
 * @param destination where it goes
 * @param loadedMiles the distance one way
 * @param isRoundTrip whether the carrier bills the way back too
 */
public record Lane(String origin, String destination, BigDecimal loadedMiles, boolean isRoundTrip) {

    private static final BigDecimal BOTH_WAYS = new BigDecimal("2");

    public Lane {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(destination, "destination");
        if (loadedMiles.signum() <= 0) {
            throw new FreightError(origin + " to " + destination + ": no distance");
        }
        if (origin.equals(destination)) {
            throw new FreightError("a lane has to go somewhere");
        }
    }

    /** A one way lane. */
    public Lane(String origin, String destination, BigDecimal loadedMiles) {
        this(origin, destination, loadedMiles, false);
    }

    /** Miles the carrier bills. A round trip lane bills both ways. */
    public BigDecimal billedMiles() {
        return loadedMiles.multiply(isRoundTrip ? BOTH_WAYS : BigDecimal.ONE);
    }

    public String label() {
        return origin + "-" + destination;
    }
}
