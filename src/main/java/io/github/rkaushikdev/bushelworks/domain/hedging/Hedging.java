package io.github.rkaushikdev.bushelworks.domain.hedging;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.domain.calendars.BusinessCalendar;
import io.github.rkaushikdev.bushelworks.domain.pricing.FuturesMonth;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/** Sizing a hedge, and rolling a month's position forward. */
public final class Hedging {

    private Hedging() {}

    /** How many whole contracts a bushel position needs, rounded toward zero. */
    public static int contractsFor(BigDecimal bushels) {
        return Decimals.divide(bushels, Lot.CONTRACT_BUSHELS)
                .setScale(0, RoundingMode.DOWN)
                .intValueExact();
    }

    /**
     * Contracts the desk is short of being flat.
     *
     * <p>Negative means more should be sold. This is the number the manager reads before he
     * telephones the broker, and it is deliberately in contracts rather than bushels because that is
     * what he will say down the telephone.
     */
    public static int hedgeGap(BigDecimal netLongBushels, Account account) {
        int wanted = -contractsFor(netLongBushels);
        return wanted - account.netContracts();
    }

    private static int netIn(Account account, FuturesMonth month) {
        Integer net = account.byMonth().get(month);
        if (net == null) {
            throw new HedgeError("nothing on in " + month.symbol() + " to roll");
        }
        return net;
    }

    /**
     * What it costs to move a month's position to the next one.
     *
     * <p>The spread times the bushels. A carry market costs money to roll and an inverse pays, and
     * which one it is changes weekly.
     */
    public static BigDecimal rollCost(Account account, FuturesMonth month, BigDecimal nearPrice, BigDecimal farPrice) {
        int net = netIn(account, month);
        BigDecimal spread = Money.price(farPrice).subtract(Money.price(nearPrice));
        return Money.amount(spread.multiply(BigDecimal.valueOf(net)).multiply(Lot.CONTRACT_BUSHELS));
    }

    /** Close a month and open the same position in the next one, fills referenced {@code ROLL-}. */
    public static List<Lot> roll(
            Account account, FuturesMonth month, BigDecimal nearPrice, BigDecimal farPrice, LocalDate on) {
        return roll(account, month, nearPrice, farPrice, on, "ROLL");
    }

    /** Close a month and open the same position in the next one. */
    public static List<Lot> roll(
            Account account,
            FuturesMonth month,
            BigDecimal nearPrice,
            BigDecimal farPrice,
            LocalDate on,
            String referencePrefix) {
        int net = netIn(account, month);
        Lot closing = new Lot(
                referencePrefix + "-" + month.symbol() + "-C",
                month,
                net < 0 ? Side.LONG : Side.SHORT,
                Math.abs(net),
                Money.price(nearPrice),
                on);
        FuturesMonth far = month.next();
        Lot opening = new Lot(
                referencePrefix + "-" + far.symbol() + "-O",
                far,
                net < 0 ? Side.SHORT : Side.LONG,
                Math.abs(net),
                Money.price(farPrice),
                on);
        account.add(closing);
        account.add(opening);
        return List.of(closing, opening);
    }

    /** The months with something on, in delivery order. */
    public static List<FuturesMonth> monthsOn(Account account) {
        return account.byMonth().keySet().stream().sorted(FuturesMonth.ORDER).toList();
    }

    /**
     * The months with something on whose first position day falls within {@code businessDays} of
     * {@code today}, or has already passed, in delivery order.
     *
     * <p>This is the list the manager reads on the Monday before the roll: anything on it either
     * rolls this week or risks a delivery notice.
     */
    public static List<FuturesMonth> dueToRoll(
            Account account, LocalDate today, int businessDays, BusinessCalendar exchange) {
        LocalDate horizon = exchange.addBusinessDays(today, businessDays);
        return monthsOn(account).stream()
                .filter(month -> month.mustRollBy(horizon, exchange))
                .toList();
    }
}
