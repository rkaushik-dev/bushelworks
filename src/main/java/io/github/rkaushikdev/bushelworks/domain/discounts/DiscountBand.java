package io.github.rkaushikdev.bushelworks.domain.discounts;

import io.github.rkaushikdev.bushelworks.domain.commodity.FactorDirection;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * One band of a schedule, measured from {@code start} toward the bad end.
 *
 * @param start where the band starts charging
 * @param rate dollars per bushel per increment
 * @param increment the step the rate is charged in
 * @param mode how a part increment is counted
 * @param end where the band stops, or nothing for a band that runs on
 */
public record DiscountBand(
        BigDecimal start, BigDecimal rate, BigDecimal increment, DiscountMode mode, Optional<BigDecimal> end) {

    public DiscountBand {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(end, "end");
        if (increment.signum() <= 0) {
            throw new ScheduleError("a discount increment must be positive");
        }
        if (rate.signum() < 0) {
            throw new ScheduleError("a discount rate cannot be negative");
        }
    }

    /** An open ended band charged a whole step per point. */
    public static DiscountBand of(BigDecimal start, BigDecimal rate) {
        return new DiscountBand(start, rate, BigDecimal.ONE, DiscountMode.STEP, Optional.empty());
    }

    public DiscountBand withIncrement(BigDecimal value) {
        return new DiscountBand(start, rate, value, mode, end);
    }

    public DiscountBand withMode(DiscountMode value) {
        return new DiscountBand(start, rate, increment, value, end);
    }

    public DiscountBand endingAt(BigDecimal value) {
        return new DiscountBand(start, rate, increment, mode, Optional.of(value));
    }

    /** How far the band reaches toward the bad end, or nothing when it runs on. */
    public Optional<BigDecimal> width(FactorDirection direction) {
        return end.map(stop -> direction == FactorDirection.LOW_IS_BAD ? start.subtract(stop) : stop.subtract(start));
    }
}
