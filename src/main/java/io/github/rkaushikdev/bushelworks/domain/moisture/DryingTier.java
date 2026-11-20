package io.github.rkaushikdev.bushelworks.domain.moisture;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * Dollars per point per bushel over a band of points.
 *
 * @param upToPoints the point the tier stops at, or nothing for the open ended last tier
 * @param ratePerPoint dollars per bushel for each point in the tier
 */
public record DryingTier(Optional<BigDecimal> upToPoints, BigDecimal ratePerPoint) {

    public DryingTier {
        Objects.requireNonNull(upToPoints, "upToPoints");
        if (upToPoints.isPresent() && upToPoints.get().signum() <= 0) {
            throw new IllegalArgumentException("a drying tier has to cover at least one point");
        }
        if (ratePerPoint.signum() < 0) {
            throw new IllegalArgumentException("a drying rate cannot be negative");
        }
    }

    /** A tier that stops at {@code upToPoints}. */
    public static DryingTier upTo(BigDecimal upToPoints, BigDecimal ratePerPoint) {
        return new DryingTier(Optional.of(upToPoints), ratePerPoint);
    }

    /** The open ended tier every point past the last ceiling falls in. */
    public static DryingTier open(BigDecimal ratePerPoint) {
        return new DryingTier(Optional.empty(), ratePerPoint);
    }
}
