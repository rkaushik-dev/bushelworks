package io.github.rkaushikdev.bushelworks.domain.hedging;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.Units;
import io.github.rkaushikdev.bushelworks.domain.pricing.FuturesMonth;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything on with the broker.
 *
 * <p>The house does not trade. It sells futures against grain it owns and buys them back when the
 * grain is sold, and the only questions it asks of the account are how many contracts are on by
 * month, what the position is worth against today's settlement price, and what it would cost to roll
 * a month forward. Nothing here places an order: orders go to the broker on the telephone and the
 * fills come back on a statement, which is what gets keyed in.
 */
public final class Account {

    private final List<Lot> lots = new ArrayList<>();

    public List<Lot> lots() {
        return List.copyOf(lots);
    }

    public void add(Lot lot) {
        if (lots.stream().anyMatch(item -> item.reference().equals(lot.reference()))) {
            throw new ConflictError(lot.reference() + " is already on the account");
        }
        lots.add(lot);
    }

    /** Net contracts on, per month in the order they were first filled, dropping months that flattened. */
    public Map<FuturesMonth, Integer> byMonth() {
        Map<FuturesMonth, Integer> totals = new LinkedHashMap<>();
        lots.forEach(lot -> totals.merge(lot.month(), lot.signedContracts(), Integer::sum));
        totals.values().removeIf(net -> net == 0);
        return totals;
    }

    public int netContracts() {
        return lots.stream().mapToInt(Lot::signedContracts).sum();
    }

    public BigDecimal netBushels() {
        return BigDecimal.valueOf(netContracts()).multiply(Lot.CONTRACT_BUSHELS);
    }

    public boolean isShort() {
        return netContracts() < 0;
    }

    /** The weighted average fill on one month, for the position sheet. */
    public BigDecimal averagePrice(FuturesMonth month) {
        List<Lot> inMonth =
                lots.stream().filter(lot -> lot.month().equals(month)).toList();
        if (inMonth.isEmpty()) {
            throw new HedgeError("nothing on in " + month.symbol());
        }
        int contracts = inMonth.stream().mapToInt(Lot::contracts).sum();
        BigDecimal weighted = BigDecimal.ZERO;
        for (Lot lot : inMonth) {
            weighted = weighted.add(lot.price().multiply(BigDecimal.valueOf(lot.contracts())));
        }
        return Units.quantize(Decimals.divide(weighted, contracts), Money.PRICE_PLACES);
    }

    /**
     * Gain or loss against today's settlement prices.
     *
     * <p>A short gains when the market falls, which is the point: the loss on the grain in the bin is
     * what it is offsetting.
     */
    public BigDecimal openValue(Map<FuturesMonth, BigDecimal> settlements) {
        BigDecimal value = BigDecimal.ZERO;
        for (Map.Entry<FuturesMonth, Integer> position : byMonth().entrySet()) {
            BigDecimal settle = settlements.get(position.getKey());
            if (settle == null) {
                throw new HedgeError(
                        "no settlement price for " + position.getKey().symbol());
            }
            BigDecimal move = settle.subtract(averagePrice(position.getKey()));
            value = value.add(
                    move.multiply(BigDecimal.valueOf(position.getValue())).multiply(Lot.CONTRACT_BUSHELS));
        }
        return Money.amount(value);
    }
}
