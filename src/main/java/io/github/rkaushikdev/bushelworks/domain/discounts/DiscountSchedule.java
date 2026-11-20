package io.github.rkaushikdev.bushelworks.domain.discounts;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.Units;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.domain.commodity.FactorDirection;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The whole ladder for one factor of one commodity: cents per bushel from one grade factor.
 *
 * <p>Every house publishes its own and they are all the same shape: a threshold where the discount
 * starts, an increment it is charged in, and a rate per increment. What differs, and what quietly
 * costs money when it is got wrong, is how the increment is counted; see {@link DiscountMode}.
 *
 * <p>Bands stack. A test weight schedule that charges one rate to 52 pounds and a steeper one below
 * that charges the first rate across the first band and the steeper rate only on the part below
 * 52, which is not what most people guess when they read the table.
 *
 * @param commodityCode the commodity the schedule is published for
 * @param factor the factor it reads
 * @param bands the bands, from the threshold toward the bad end, each starting where the last ends
 * @param label what the statement calls the discount
 */
public record DiscountSchedule(String commodityCode, Factor factor, List<DiscountBand> bands, String label) {

    public DiscountSchedule {
        Objects.requireNonNull(commodityCode, "commodityCode");
        Objects.requireNonNull(factor, "factor");
        Objects.requireNonNull(label, "label");
        bands = List.copyOf(bands);
        String where = commodityCode + "/" + factor.value();
        if (bands.isEmpty()) {
            throw new ScheduleError(where + ": no bands");
        }
        FactorDirection direction = factor.direction();
        DiscountBand previous = null;
        for (DiscountBand band : bands) {
            if (previous != null) {
                if (previous.end().isEmpty()) {
                    throw new ScheduleError(where + ": a band follows an open ended band");
                }
                if (band.start().compareTo(previous.end().get()) != 0) {
                    throw new ScheduleError(where + ": gap or overlap at " + band.start());
                }
            }
            if (band.end().isPresent()) {
                int toward = band.end().get().compareTo(band.start());
                if (direction == FactorDirection.LOW_IS_BAD && toward >= 0) {
                    throw new ScheduleError(where + ": a low is bad band has to descend");
                }
                if (direction == FactorDirection.HIGH_IS_BAD && toward <= 0) {
                    throw new ScheduleError(where + ": a high is bad band has to ascend");
                }
            }
            previous = band;
        }
    }

    /** A schedule with no label of its own. */
    public DiscountSchedule(String commodityCode, Factor factor, List<DiscountBand> bands) {
        this(commodityCode, factor, bands, "");
    }

    public FactorDirection direction() {
        return factor.direction();
    }

    /** The value at which this schedule starts costing anything. */
    public BigDecimal threshold() {
        return bands.get(0).start();
    }

    /** How far past the threshold this reading is, floored at zero. */
    public BigDecimal excess(BigDecimal value) {
        BigDecimal over =
                direction() == FactorDirection.LOW_IS_BAD ? threshold().subtract(value) : value.subtract(threshold());
        return over.signum() > 0 ? over : BigDecimal.ZERO;
    }

    /** Dollars per bushel of discount for this factor reading. */
    public BigDecimal rateFor(BigDecimal value) {
        BigDecimal remaining = excess(value);
        if (remaining.signum() <= 0) {
            return Money.ZERO_PRICE;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (DiscountBand band : bands) {
            if (remaining.signum() <= 0) {
                break;
            }
            Optional<BigDecimal> width = band.width(direction());
            BigDecimal span = width.isEmpty() ? remaining : Decimals.min(remaining, width.get());
            if (span.signum() <= 0) {
                continue;
            }
            total = total.add(bandCharge(band, span));
            remaining = remaining.subtract(span);
        }
        return Units.quantize(total, Money.PRICE_PLACES);
    }

    /** Dollars of discount on a load of this size. */
    public BigDecimal charge(BigDecimal bushels, BigDecimal value) {
        return Money.extend(bushels, rateFor(value));
    }

    private static BigDecimal bandCharge(DiscountBand band, BigDecimal span) {
        if (band.mode() == DiscountMode.FLAT) {
            return band.rate();
        }
        BigDecimal steps = Decimals.divide(span, band.increment());
        if (band.mode() == DiscountMode.STEP) {
            steps = steps.setScale(0, RoundingMode.CEILING);
        }
        return steps.multiply(band.rate());
    }
}
