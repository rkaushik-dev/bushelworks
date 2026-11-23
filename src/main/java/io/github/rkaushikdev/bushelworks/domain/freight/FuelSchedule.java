package io.github.rkaushikdev.bushelworks.domain.freight;

import io.github.rkaushikdev.bushelworks.Decimals;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * The table a carrier works its fuel surcharge from, off the week's diesel price.
 *
 * <p>Nobody quotes a surcharge out of the air. The carrier pegs it to the government's weekly
 * on-highway diesel average: nothing at or below a peg price, and so many percent of the line haul
 * for every step of so many cents above it, a part step counting as a whole one. The house checks
 * the carrier's invoice by working the same table on the same week's price.
 *
 * @param pegPrice the diesel price, dollars a gallon, at or below which there is no surcharge
 * @param step the cents a gallon each step of the table covers, as dollars
 * @param percentPerStep the surcharge added for each step above the peg
 */
public record FuelSchedule(BigDecimal pegPrice, BigDecimal step, BigDecimal percentPerStep) {

    public FuelSchedule {
        Objects.requireNonNull(pegPrice, "pegPrice");
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(percentPerStep, "percentPerStep");
        if (pegPrice.signum() < 0) {
            throw new FreightError("a fuel peg cannot be negative");
        }
        if (step.signum() <= 0) {
            throw new FreightError("a fuel table step has to be more than nothing");
        }
        if (percentPerStep.signum() < 0) {
            throw new FreightError("a fuel table cannot take percent off");
        }
    }

    /** The surcharge percent for a week whose diesel averaged {@code dieselPrice} a gallon. */
    public BigDecimal percentAt(BigDecimal dieselPrice) {
        BigDecimal over = dieselPrice.subtract(pegPrice);
        if (over.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal steps = Decimals.divide(over, step).setScale(0, RoundingMode.CEILING);
        return steps.multiply(percentPerStep);
    }
}
