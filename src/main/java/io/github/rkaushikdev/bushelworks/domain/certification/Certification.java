package io.github.rkaushikdev.bushelworks.domain.certification;

import io.github.rkaushikdev.bushelworks.Units;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What an out of tolerance scale did to the loads weighed on it.
 *
 * <p>A scale reading two hundred pounds light for three weeks is a specific amount of money owed to
 * a specific list of producers, and working it out from a filing cabinet takes a fortnight.
 */
public final class Certification {

    private Certification() {}

    /**
     * What a load weighed on an out of tolerance scale should have been, as a correction in pounds.
     *
     * <p>A scale reading heavy overpaid the producer, so the correction is negative. Applying it is
     * a decision for the manager and the state, not for software.
     */
    public static BigDecimal correctionFor(ScaleTest test, BigDecimal netPounds) {
        return Units.quantize(netPounds.negate().multiply(test.errorFraction()), BigDecimal.ONE);
    }

    /** Total pounds in question across the suspect window. */
    public static BigDecimal exposure(ScaleTest test, BigDecimal netPoundsInWindow) {
        return correctionFor(test, netPoundsInWindow).abs();
    }

    /** Scales the state would not let the house run today. */
    public static List<ScaleLog> overdueCertifications(List<ScaleLog> logs, LocalDate day) {
        return logs.stream().filter(log -> !log.certifiedOn(day)).toList();
    }
}
