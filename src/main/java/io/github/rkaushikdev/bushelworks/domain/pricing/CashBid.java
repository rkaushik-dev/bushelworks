package io.github.rkaushikdev.bushelworks.domain.pricing;

import io.github.rkaushikdev.bushelworks.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * What the board shows: futures plus basis for one delivery slot.
 *
 * <p>A country cash bid is not a number somebody types. It is a futures price for a delivery month
 * plus a basis, and the two move independently. The board shows the sum, the contract file keeps
 * the parts, and a basis contract prices the parts on different days on purpose.
 *
 * @param commodityCode the commodity bid for
 * @param month the futures month the bid is built on
 * @param futures the futures price
 * @param basis the basis against that month
 * @param quotedOn the day of the bid
 * @param locationCode the location it is good at, or {@code *} for every location
 */
public record CashBid(
        String commodityCode,
        FuturesMonth month,
        BigDecimal futures,
        BigDecimal basis,
        LocalDate quotedOn,
        String locationCode) {

    public CashBid {
        Objects.requireNonNull(commodityCode, "commodityCode");
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(quotedOn, "quotedOn");
        Objects.requireNonNull(locationCode, "locationCode");
        if (futures.signum() <= 0) {
            throw new PricingError("a futures price has to be positive");
        }
        if (!month.commodityCode().equals(commodityCode)) {
            throw new PricingError(month.symbol() + " is not a " + commodityCode + " contract");
        }
    }

    /** A bid good at every location. */
    public CashBid(String commodityCode, FuturesMonth month, BigDecimal futures, BigDecimal basis, LocalDate quotedOn) {
        this(commodityCode, month, futures, basis, quotedOn, "*");
    }

    /**
     * The posted cash price, floored at zero.
     *
     * <p>A basis wide enough to drive the cash bid below zero happens in a harvest pile up. The
     * board shows zero and the desk stops buying rather than printing a negative bid.
     */
    public BigDecimal cash() {
        BigDecimal raw = futures.add(basis);
        return Money.price(raw.signum() > 0 ? raw : BigDecimal.ZERO);
    }

    /** The same bid at another basis. */
    public CashBid withBasis(BigDecimal value) {
        return new CashBid(commodityCode, month, futures, Money.price(value), quotedOn, locationCode);
    }
}
