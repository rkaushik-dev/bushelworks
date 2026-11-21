package io.github.rkaushikdev.bushelworks.domain.position;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Units;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/** Hedging a position line, measuring it against a bin count, and carrying it to the next day. */
public final class Positions {

    private static final BigDecimal CONTRACT_SIZE = new BigDecimal("5000");

    private Positions() {}

    /** How many five thousand bushel contracts would flatten this position. */
    public static int hedgeRequirement(PositionLine line) {
        return hedgeRequirement(line, CONTRACT_SIZE);
    }

    /**
     * How many futures contracts would flatten this position.
     *
     * <p>Rounded toward zero on purpose. A house that rounds its hedge up is trading, and the board
     * does not let it call that hedging.
     */
    public static int hedgeRequirement(PositionLine line, BigDecimal contractSize) {
        if (contractSize.signum() <= 0) {
            throw new PositionError("a futures contract cannot be zero bushels");
        }
        return Decimals.divide(line.netLong(), contractSize)
                .setScale(0, RoundingMode.DOWN)
                .intValueExact();
    }

    /**
     * Compare a measured bin count against the book and refuse a wild gap.
     *
     * <p>Bin measurement is good to about half a percent on a good day. A variance inside tolerance is
     * written off to shrink and dust; one outside it means somebody has a ticket in a drawer.
     */
    public static BigDecimal reconcile(PositionLine line, BigDecimal counted, BigDecimal tolerance) {
        if (tolerance.signum() < 0) {
            throw new PositionError("tolerance cannot be negative");
        }
        BigDecimal variance = Units.quantize(counted.subtract(line.onHand()), Units.BUSHEL_PLACES);
        BigDecimal allowed = Units.quantize(
                Decimals.divide(line.onHand().multiply(tolerance), Decimals.HUNDRED), Units.BUSHEL_PLACES);
        if (variance.abs().compareTo(allowed) > 0) {
            throw new PositionError(line.commodityCode() + "/" + line.locationCode() + ": measured variance " + variance
                    + " exceeds the " + tolerance + "% tolerance of " + allowed);
        }
        return variance;
    }

    /** Carry yesterday's lines onto today before the day's activity is applied. */
    public static List<PositionLine> rollForward(List<PositionLine> lines, LocalDate toDay) {
        return lines.stream().map(line -> line.toBuilder().asOf(toDay).build()).toList();
    }
}
