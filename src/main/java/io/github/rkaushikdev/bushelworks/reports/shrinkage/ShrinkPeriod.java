package io.github.rkaushikdev.bushelworks.reports.shrinkage;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Units;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One commodity at one site over a span: the shrink charged against the shrink measured.
 *
 * <p>Nothing here recomputes shrink. Both figures come in from outside, one off the settlements and
 * one off the scale, and this only compares them.
 *
 * @param commodityCode the commodity
 * @param locationCode the site
 * @param opens the first day of the span
 * @param closes the last day of the span
 * @param wetBushelsIn the bushels received before shrink
 * @param shrinkCharged the shrink taken off the producers
 * @param measuredLoss what the scale says actually went missing
 * @param driedBushels how many of the bushels went through the dryer
 */
public record ShrinkPeriod(
        String commodityCode,
        String locationCode,
        LocalDate opens,
        LocalDate closes,
        BigDecimal wetBushelsIn,
        BigDecimal shrinkCharged,
        BigDecimal measuredLoss,
        BigDecimal driedBushels) {

    private static final BigDecimal PERCENT_PLACES = new BigDecimal("0.0001");
    private static final BigDecimal NO_PERCENT = new BigDecimal("0.0000");
    private static final BigDecimal DRIED_PLACES = new BigDecimal("0.1");
    private static final BigDecimal NONE_DRIED = new BigDecimal("0.0");

    public ShrinkPeriod {
        Objects.requireNonNull(commodityCode, "commodityCode");
        Objects.requireNonNull(locationCode, "locationCode");
        Objects.requireNonNull(opens, "opens");
        Objects.requireNonNull(closes, "closes");
        Objects.requireNonNull(wetBushelsIn, "wetBushelsIn");
        Objects.requireNonNull(shrinkCharged, "shrinkCharged");
        Objects.requireNonNull(measuredLoss, "measuredLoss");
        Objects.requireNonNull(driedBushels, "driedBushels");
        if (closes.isBefore(opens)) {
            throw new ShrinkageError(commodityCode + ": the period closes before it opens");
        }
        if (wetBushelsIn.signum() < 0 || shrinkCharged.signum() < 0) {
            throw new ShrinkageError(commodityCode + ": negative bushels");
        }
    }

    /** A period to fill in; nothing was dried until told otherwise. */
    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .commodityCode(commodityCode)
                .locationCode(locationCode)
                .opens(opens)
                .closes(closes)
                .wetBushelsIn(wetBushelsIn)
                .shrinkCharged(shrinkCharged)
                .measuredLoss(measuredLoss)
                .driedBushels(driedBushels);
    }

    /**
     * Charged less measured, at bushel places. Positive is in the house's favour: the shrink table is
     * generous and the state will eventually ask about it. Negative means the house is paying for
     * grain it does not have, which is worse and much quieter.
     */
    public BigDecimal gain() {
        return Units.quantize(shrinkCharged.subtract(measuredLoss), Units.BUSHEL_PLACES);
    }

    /** The shrink charged as a percentage of the wet bushels, at four places. */
    public BigDecimal chargedPercent() {
        return percentOfWet(shrinkCharged);
    }

    /** The loss measured as a percentage of the wet bushels, at four places. */
    public BigDecimal measuredPercent() {
        return percentOfWet(measuredLoss);
    }

    /** Whether the gain, either way, is more than {@link Shrinkage#NOTABLE} of the wet bushels. */
    public boolean isNotable() {
        if (wetBushelsIn.signum() <= 0) {
            return false;
        }
        return gain().abs().compareTo(wetBushelsIn.multiply(Shrinkage.NOTABLE)) > 0;
    }

    /** Which way the gain runs, in words. */
    public String direction() {
        int sign = gain().signum();
        if (sign > 0) {
            return "in the house's favour";
        }
        if (sign < 0) {
            return "against the house";
        }
        return "flat";
    }

    /** The bushels dried as a percentage of the wet bushels, to a tenth. */
    public BigDecimal percentDried() {
        if (wetBushelsIn.signum() <= 0) {
            return NONE_DRIED;
        }
        return Units.quantize(Decimals.divide(driedBushels, wetBushelsIn).multiply(Decimals.HUNDRED), DRIED_PLACES);
    }

    private BigDecimal percentOfWet(BigDecimal bushels) {
        if (wetBushelsIn.signum() <= 0) {
            return NO_PERCENT;
        }
        return Units.quantize(Decimals.divide(bushels, wetBushelsIn).multiply(Decimals.HUNDRED), PERCENT_PLACES);
    }

    /** A shrink period assembled a field at a time. */
    public static final class Builder {

        private String commodityCode;
        private String locationCode;
        private LocalDate opens;
        private LocalDate closes;
        private BigDecimal wetBushelsIn;
        private BigDecimal shrinkCharged;
        private BigDecimal measuredLoss;
        private BigDecimal driedBushels = BigDecimal.ZERO;

        private Builder() {}

        public Builder commodityCode(String value) {
            this.commodityCode = value;
            return this;
        }

        public Builder locationCode(String value) {
            this.locationCode = value;
            return this;
        }

        public Builder opens(LocalDate value) {
            this.opens = value;
            return this;
        }

        public Builder closes(LocalDate value) {
            this.closes = value;
            return this;
        }

        public Builder wetBushelsIn(BigDecimal value) {
            this.wetBushelsIn = value;
            return this;
        }

        public Builder shrinkCharged(BigDecimal value) {
            this.shrinkCharged = value;
            return this;
        }

        public Builder measuredLoss(BigDecimal value) {
            this.measuredLoss = value;
            return this;
        }

        public Builder driedBushels(BigDecimal value) {
            this.driedBushels = value;
            return this;
        }

        public ShrinkPeriod build() {
            return new ShrinkPeriod(
                    commodityCode,
                    locationCode,
                    opens,
                    closes,
                    wetBushelsIn,
                    shrinkCharged,
                    measuredLoss,
                    driedBushels);
        }
    }
}
