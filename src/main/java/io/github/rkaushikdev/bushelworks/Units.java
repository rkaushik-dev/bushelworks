package io.github.rkaushikdev.bushelworks;

import java.math.BigDecimal;

/**
 * Weight and volume conversions used everywhere in the scale house.
 *
 * <p>Grain moves through a country elevator as pounds on a truck scale and leaves as bushels on a
 * settlement statement. The conversion is not a physical constant: a bushel is defined per
 * commodity as a number of pounds of test weight, and the industry rounds it in a specific way at a
 * specific place. Getting the rounding wrong by one ten-thousandth of a bushel is enough to make a
 * producer's cheque disagree with the elevator's ledger, so all of it happens here and nowhere
 * else.
 */
public final class Units {

    /** Pounds. Truck scales report whole pounds, and every weight here has been rounded to one. */
    public static final BigDecimal POUND_PLACES = BigDecimal.ONE;

    /** Bushels carry four decimal places on a scale ticket. No printed ticket has shown five. */
    public static final BigDecimal BUSHEL_PLACES = new BigDecimal("0.0001");

    /** Hundredweight, used for sunflowers and for anything sold by the ton. */
    public static final BigDecimal CWT_PLACES = new BigDecimal("0.01");

    public static final BigDecimal TON_PLACES = new BigDecimal("0.001");

    public static final BigDecimal POUNDS_PER_CWT = new BigDecimal("100");
    public static final BigDecimal POUNDS_PER_TON = new BigDecimal("2000");

    private Units() {}

    /**
     * Round half up at a stated precision.
     *
     * <p>Every rounding in the package goes through here so there is exactly one answer to "which
     * way does a half go", and it is up.
     */
    public static BigDecimal quantize(BigDecimal value, BigDecimal places) {
        return Decimals.quantize(value, places);
    }

    /**
     * Coerce a weight to whole pounds.
     *
     * <p>Anything that reaches the settlement path has already been through a scale head, so a
     * fractional pound means somebody typed it. It is rounded rather than rejected because the
     * alternative is a stuck ticket at four in the morning.
     */
    public static BigDecimal toPounds(BigDecimal weight) {
        if (weight.signum() < 0) {
            throw new UnitError("weight cannot be negative: " + weight);
        }
        return quantize(weight, POUND_PLACES);
    }

    public static BigDecimal toPounds(String weight) {
        return toPounds(Decimals.parse(weight));
    }

    public static BigDecimal toPounds(long weight) {
        return toPounds(BigDecimal.valueOf(weight));
    }

    /** Gross minus tare, guarded against a tare that outweighs the load. */
    public static BigDecimal netPounds(BigDecimal gross, BigDecimal tare) {
        BigDecimal g = toPounds(gross);
        BigDecimal t = toPounds(tare);
        if (t.compareTo(g) > 0) {
            throw new UnitError("tare " + t + " exceeds gross " + g);
        }
        return g.subtract(t);
    }

    public static BigDecimal netPounds(long gross, long tare) {
        return netPounds(BigDecimal.valueOf(gross), BigDecimal.valueOf(tare));
    }

    /**
     * Convert pounds to bushels at a commodity's standard test weight.
     *
     * <p>The divisor is the <em>standard</em> pounds per bushel for the commodity, not the test
     * weight measured on the probe sample. Corn is 56 whether the load tested 52 or 58; a light load
     * is handled by the test weight discount schedule and never by moving the divisor.
     */
    public static BigDecimal poundsToBushels(BigDecimal pounds, BigDecimal standardTestWeight) {
        if (standardTestWeight.signum() <= 0) {
            throw new UnitError("standard test weight must be positive: " + standardTestWeight);
        }
        return quantize(Decimals.divide(toPounds(pounds), standardTestWeight), BUSHEL_PLACES);
    }

    public static BigDecimal poundsToBushels(long pounds, BigDecimal standardTestWeight) {
        return poundsToBushels(BigDecimal.valueOf(pounds), standardTestWeight);
    }

    public static BigDecimal bushelsToPounds(BigDecimal bushels, BigDecimal standardTestWeight) {
        if (standardTestWeight.signum() <= 0) {
            throw new UnitError("standard test weight must be positive: " + standardTestWeight);
        }
        return quantize(bushels.multiply(standardTestWeight), POUND_PLACES);
    }

    public static BigDecimal poundsToCwt(BigDecimal pounds) {
        return quantize(Decimals.divide(toPounds(pounds), POUNDS_PER_CWT), CWT_PLACES);
    }

    public static BigDecimal poundsToCwt(long pounds) {
        return poundsToCwt(BigDecimal.valueOf(pounds));
    }

    public static BigDecimal poundsToTons(BigDecimal pounds) {
        return quantize(Decimals.divide(toPounds(pounds), POUNDS_PER_TON), TON_PLACES);
    }

    public static BigDecimal poundsToTons(long pounds) {
        return poundsToTons(BigDecimal.valueOf(pounds));
    }

    /** Take a percentage of a quantity, rounded to bushel precision. */
    public static BigDecimal percentOf(BigDecimal value, BigDecimal percent) {
        return percentOf(value, percent, BUSHEL_PLACES);
    }

    /** Take a percentage of a quantity, rounded at the caller's precision. */
    public static BigDecimal percentOf(BigDecimal value, BigDecimal percent, BigDecimal places) {
        return quantize(Decimals.divide(value.multiply(percent), Decimals.HUNDRED), places);
    }
}
