package io.github.rkaushikdev.bushelworks.domain.moisture;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Units;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * How one commodity's water comes off the bushel count.
 *
 * <p>Wet grain is sold by the elevator dry, so the water has to come off before anything is
 * priced. Both methods are in use on the same driveway: a house will shrink its own storage on
 * water and its purchases on the table. Getting this wrong shows up as a slow inventory drift that
 * nobody can find for two years.
 *
 * @param commodityCode the commodity
 * @param baseMoisture the moisture percent the price assumes
 * @param method which convention takes the water off
 * @param shrinkFactor percent of shrink per point over base, for the table method
 * @param handlingLossPercent percent added to the water shrink for what the dryer loses
 * @param rejectAbove the moisture above which the house will not take the load, if it has one
 */
public record ShrinkSchedule(
        String commodityCode,
        BigDecimal baseMoisture,
        ShrinkMethod method,
        BigDecimal shrinkFactor,
        BigDecimal handlingLossPercent,
        Optional<BigDecimal> rejectAbove) {

    /** Shrink percentages carry four places. */
    public static final BigDecimal PERCENT_PLACES = new BigDecimal("0.0001");

    public ShrinkSchedule {
        Objects.requireNonNull(commodityCode, "commodityCode");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(rejectAbove, "rejectAbove");
        if (baseMoisture.signum() <= 0 || baseMoisture.compareTo(Decimals.HUNDRED) >= 0) {
            throw new IllegalArgumentException(commodityCode + ": base moisture out of range");
        }
        if (shrinkFactor.signum() < 0) {
            throw new IllegalArgumentException(commodityCode + ": shrink factor cannot be negative");
        }
        if (handlingLossPercent.signum() < 0) {
            throw new IllegalArgumentException(commodityCode + ": handling loss cannot be negative");
        }
    }

    /** The table method at 1.18 a point, no handling loss and no moisture limit. */
    public static ShrinkSchedule of(String commodityCode, BigDecimal baseMoisture) {
        return new ShrinkSchedule(
                commodityCode,
                baseMoisture,
                ShrinkMethod.TABLE,
                new BigDecimal("1.18"),
                BigDecimal.ZERO,
                Optional.empty());
    }

    public ShrinkSchedule withMethod(ShrinkMethod value) {
        return new ShrinkSchedule(commodityCode, baseMoisture, value, shrinkFactor, handlingLossPercent, rejectAbove);
    }

    public ShrinkSchedule withShrinkFactor(BigDecimal value) {
        return new ShrinkSchedule(commodityCode, baseMoisture, method, value, handlingLossPercent, rejectAbove);
    }

    public ShrinkSchedule withHandlingLossPercent(BigDecimal value) {
        return new ShrinkSchedule(commodityCode, baseMoisture, method, shrinkFactor, value, rejectAbove);
    }

    public ShrinkSchedule withRejectAbove(BigDecimal value) {
        return new ShrinkSchedule(
                commodityCode, baseMoisture, method, shrinkFactor, handlingLossPercent, Optional.of(value));
    }

    /**
     * Points of moisture above base, floored at zero.
     *
     * <p>Dry grain does not shrink upward. A load at 12.4 against a 15.0 base is zero points, not
     * minus 2.6, and the premium some houses pay for dry corn is a separate line item rather than a
     * negative shrink.
     */
    public BigDecimal pointsOver(BigDecimal moisture) {
        BigDecimal over = moisture.subtract(baseMoisture);
        return over.signum() > 0 ? over : BigDecimal.ZERO;
    }

    /** Percentage of the wet bushels that shrink away. */
    public BigDecimal shrinkPercent(BigDecimal moisture) {
        if (moisture.signum() < 0 || moisture.compareTo(Decimals.HUNDRED) >= 0) {
            throw new MoistureError("moisture out of range: " + moisture);
        }
        if (rejectAbove.isPresent() && moisture.compareTo(rejectAbove.get()) > 0) {
            throw new MoistureError(
                    commodityCode + ": " + moisture + "% is above the " + rejectAbove.get() + "% limit");
        }
        BigDecimal points = pointsOver(moisture);
        if (points.signum() == 0 || method == ShrinkMethod.NONE) {
            return new BigDecimal("0.0000");
        }
        if (method == ShrinkMethod.WATER) {
            BigDecimal water = Decimals.divide(points, Decimals.HUNDRED.subtract(baseMoisture))
                    .multiply(Decimals.HUNDRED);
            return Units.quantize(water.add(handlingLossPercent), PERCENT_PLACES);
        }
        return Units.quantize(points.multiply(shrinkFactor), PERCENT_PLACES);
    }

    /** Wet bushels less the shrink, at bushel precision. */
    public BigDecimal dryBushels(BigDecimal wetBushels, BigDecimal moisture) {
        BigDecimal percent = shrinkPercent(moisture);
        BigDecimal kept = Decimals.divide(Decimals.HUNDRED.subtract(percent), Decimals.HUNDRED);
        return Units.quantize(wetBushels.multiply(kept), Units.BUSHEL_PLACES);
    }

    /** The bushels that disappeared. Wet less dry, so the two always add up. */
    public BigDecimal shrunkBushels(BigDecimal wetBushels, BigDecimal moisture) {
        return Units.quantize(wetBushels.subtract(dryBushels(wetBushels, moisture)), Units.BUSHEL_PLACES);
    }
}
