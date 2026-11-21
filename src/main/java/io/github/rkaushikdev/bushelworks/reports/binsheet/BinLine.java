package io.github.rkaushikdev.bushelworks.reports.binsheet;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.persistence.models.BinReadingRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;

/**
 * One bin, flattened for printing.
 *
 * @param binId the bin
 * @param commodityCode what is in it; empty when nothing is
 * @param bushels how much is in it
 * @param capacity how much it holds when full to the eaves
 * @param moisture what the blend in it tests for moisture, when it has been measured
 * @param testWeight what the blend tests for test weight, when it has been measured
 * @param headroom what it will still take before it is called full
 * @param warmest the warmest cable reading over the span, when there was one
 * @param fanRunning whether the fan was on for any reading over the span
 * @param outOfService whether the bin is out of service
 */
public record BinLine(
        String binId,
        String commodityCode,
        BigDecimal bushels,
        BigDecimal capacity,
        Optional<BigDecimal> moisture,
        Optional<BigDecimal> testWeight,
        BigDecimal headroom,
        Optional<BigDecimal> warmest,
        boolean fanRunning,
        boolean outOfService) {

    public BinLine {
        Objects.requireNonNull(binId, "binId");
        Objects.requireNonNull(commodityCode, "commodityCode");
        Objects.requireNonNull(bushels, "bushels");
        Objects.requireNonNull(capacity, "capacity");
        Objects.requireNonNull(moisture, "moisture");
        Objects.requireNonNull(testWeight, "testWeight");
        Objects.requireNonNull(headroom, "headroom");
        Objects.requireNonNull(warmest, "warmest");
    }

    /** How full the bin is, as a percent of its whole capacity to a tenth, a tie going to the even tenth. */
    public BigDecimal percentFull() {
        if (capacity.signum() <= 0) {
            return new BigDecimal("0.0");
        }
        return Decimals.divide(bushels, capacity).multiply(Decimals.HUNDRED).setScale(1, RoundingMode.HALF_EVEN);
    }

    /** Whether a reading over the span was above the working temperature. */
    public boolean isWarm() {
        return warmest.filter(degrees -> degrees.compareTo(BinReadingRow.WARM) > 0)
                .isPresent();
    }

    /** Warm with the fan off, which is the one that spoils. */
    public boolean needsAttention() {
        return isWarm() && !fanRunning;
    }
}
