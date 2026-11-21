package io.github.rkaushikdev.bushelworks.reports.fulfilment;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Units;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * One contract, assessed against the calendar.
 *
 * @param number the contract number
 * @param partyCode the producer
 * @param commodityCode the commodity contracted
 * @param contracted the bushels contracted
 * @param applied the bushels delivered against it so far
 * @param closes the last day of the delivery window
 * @param asOf the day it is assessed on
 * @param weeklyRate the bushels a week the producer has been hauling
 * @param tolerancePercent how far under the contracted bushels still counts as filled
 */
public record FulfilmentLine(
        String number,
        String partyCode,
        String commodityCode,
        BigDecimal contracted,
        BigDecimal applied,
        LocalDate closes,
        LocalDate asOf,
        BigDecimal weeklyRate,
        BigDecimal tolerancePercent) {

    private static final BigDecimal DAYS_A_WEEK = BigDecimal.valueOf(7);
    private static final BigDecimal NOTHING_OUTSTANDING = new BigDecimal("0.0000");

    public FulfilmentLine {
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(partyCode, "partyCode");
        Objects.requireNonNull(commodityCode, "commodityCode");
        Objects.requireNonNull(contracted, "contracted");
        Objects.requireNonNull(applied, "applied");
        Objects.requireNonNull(closes, "closes");
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(weeklyRate, "weeklyRate");
        Objects.requireNonNull(tolerancePercent, "tolerancePercent");
    }

    /** The bushels still to come before the contract is inside its tolerance, never below zero. */
    public BigDecimal outstanding() {
        BigDecimal floor =
                Decimals.divide(contracted.multiply(Decimals.HUNDRED.subtract(tolerancePercent)), Decimals.HUNDRED);
        BigDecimal left = floor.subtract(applied);
        return left.signum() > 0 ? Units.quantize(left, Units.BUSHEL_PLACES) : NOTHING_OUTSTANDING;
    }

    /** The weeks until the window shuts, in fractions of a week; none once it has shut. */
    public BigDecimal weeksLeft() {
        long days = ChronoUnit.DAYS.between(asOf, closes);
        return days > 0 ? Decimals.divide(BigDecimal.valueOf(days), DAYS_A_WEEK) : BigDecimal.ZERO;
    }

    /** What the producer can be expected to haul before the window shuts. */
    public BigDecimal deliverable() {
        return Units.quantize(weeklyRate.multiply(weeksLeft()), Units.BUSHEL_PLACES);
    }

    public FulfilmentState state() {
        if (outstanding().signum() <= 0) {
            return FulfilmentState.FILLED;
        }
        if (asOf.isAfter(closes)) {
            return FulfilmentState.SHORT;
        }
        if (weeklyRate.signum() <= 0) {
            return FulfilmentState.AT_RISK;
        }
        return deliverable().compareTo(outstanding()) >= 0 ? FulfilmentState.ON_TRACK : FulfilmentState.AT_RISK;
    }

    /** At risk or short: the desk rings the producer today. */
    public boolean needsACall() {
        FulfilmentState state = state();
        return state == FulfilmentState.AT_RISK || state == FulfilmentState.SHORT;
    }
}
