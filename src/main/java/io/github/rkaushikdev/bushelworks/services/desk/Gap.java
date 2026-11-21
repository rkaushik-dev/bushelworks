package io.github.rkaushikdev.bushelworks.services.desk;

import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.Units;
import io.github.rkaushikdev.bushelworks.domain.hedging.Lot;
import io.github.rkaushikdev.bushelworks.domain.hedging.Side;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * One commodity, and how far the hedge is from the position.
 *
 * <p>A gap can be built by hand for a what-if as readily as off the books.
 *
 * @param commodityCode the commodity
 * @param netLong the bushels the house carries the price risk on
 * @param hedgedBushels the bushels the broker has the house short
 * @param wantContracts the whole contracts the position calls for
 * @param onContracts the contracts the house is short with the broker
 */
public record Gap(
        String commodityCode, BigDecimal netLong, BigDecimal hedgedBushels, int wantContracts, int onContracts) {

    public Gap {
        Objects.requireNonNull(commodityCode, "commodityCode");
        Objects.requireNonNull(netLong, "netLong");
        Objects.requireNonNull(hedgedBushels, "hedgedBushels");
    }

    /** Positive means sell more. Negative means the house is over hedged. */
    public int gapContracts() {
        return wantContracts - onContracts;
    }

    /** The gap in bushels. */
    public BigDecimal gapBushels() {
        return Units.quantize(BigDecimal.valueOf(gapContracts()).multiply(Lot.CONTRACT_BUSHELS), Units.BUSHEL_PLACES);
    }

    /** The side an order closing the gap would be on: short to sell more, long to buy back. */
    public Side side() {
        return gapContracts() > 0 ? Side.SHORT : Side.LONG;
    }

    /** Whether the gap is at least {@link Desk#TOLERANCE_CONTRACTS} either way, worth ringing the broker about. */
    public boolean isActionable() {
        return Math.abs(gapContracts()) >= Desk.TOLERANCE_CONTRACTS;
    }

    /** Short more bushels than the house owns, which is a speculation. */
    public boolean isOverHedged() {
        return gapContracts() < 0;
    }

    /** What the fills closing the gap would be worth at a price, ignoring commission. Never negative. */
    public BigDecimal cost(BigDecimal settle) {
        return Money.amount(gapBushels().abs().multiply(settle));
    }
}
