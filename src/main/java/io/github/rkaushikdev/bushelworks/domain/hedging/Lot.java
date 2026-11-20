package io.github.rkaushikdev.bushelworks.domain.hedging;

import io.github.rkaushikdev.bushelworks.domain.pricing.FuturesMonth;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One fill off the broker's statement.
 *
 * <p>Contracts are whole. There is no such thing as three tenths of a corn contract, and rounding a
 * hedge to fractions is how a house ends up with a position it cannot explain.
 *
 * @param reference the fill
 * @param month the futures month
 * @param side short or long
 * @param contracts how many contracts
 * @param price the fill price
 * @param filledOn when
 */
public record Lot(
        String reference, FuturesMonth month, Side side, int contracts, BigDecimal price, LocalDate filledOn) {

    /** Bushels in one futures contract. */
    public static final BigDecimal CONTRACT_BUSHELS = new BigDecimal("5000");

    public Lot {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(filledOn, "filledOn");
        if (contracts <= 0) {
            throw new HedgeError(reference + ": a fill needs contracts");
        }
        if (price.signum() <= 0) {
            throw new HedgeError(reference + ": a fill needs a price");
        }
    }

    public BigDecimal bushels() {
        return BigDecimal.valueOf(contracts).multiply(CONTRACT_BUSHELS);
    }

    /** Contracts as they count toward the position: a short is negative. */
    public int signedContracts() {
        return side == Side.SHORT ? -contracts : contracts;
    }
}
