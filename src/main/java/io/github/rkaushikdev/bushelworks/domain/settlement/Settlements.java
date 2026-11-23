package io.github.rkaushikdev.bushelworks.domain.settlement;

import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.domain.tickets.SplitRole;
import io.github.rkaushikdev.bushelworks.domain.tickets.SplitShare;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Splitting a statement among the parties on a load, and adding statements up. */
public final class Settlements {

    private Settlements() {}

    /**
     * Divide a finished net across the parties on the ticket, in split order, every line by share.
     *
     * <p>Every line is split rather than only the net, so a rounding cent lands on the same party for
     * the gross and for the discounts and the two do not drift apart on the printed statements.
     */
    public static Map<String, BigDecimal> split(Settlement settlement, List<SplitShare> shares) {
        return split(settlement, shares, LeaseTerms.SHARED);
    }

    /**
     * Divide a finished net across the parties on the ticket, in split order, under a lease.
     *
     * <p>A line the lease puts on one role goes wholly to the shares holding that role, split
     * between them by their percents when there are two; every other line is split across everyone.
     *
     * @throws SettlementError when there is nobody to split across, or the lease puts a line on a
     *     role nobody on the split holds
     */
    public static Map<String, BigDecimal> split(Settlement settlement, List<SplitShare> shares, LeaseTerms lease) {
        if (shares.isEmpty()) {
            throw new SettlementError(settlement.number() + ": nothing to split across");
        }
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        shares.forEach(share -> totals.put(share.partyId(), Money.ZERO_AMOUNT));
        for (SettlementLine line : settlement.lines()) {
            List<SplitShare> bearing = lease.bearerOf(line.kind())
                    .map(role -> holding(settlement, shares, role, line))
                    .orElse(shares);
            List<BigDecimal> parts = Money.allocate(
                    line.signed(), bearing.stream().map(SplitShare::percent).toList());
            for (int index = 0; index < bearing.size(); index++) {
                totals.merge(
                        bearing.get(index).partyId(), parts.get(index), (sum, part) -> Money.amount(sum.add(part)));
            }
        }
        return totals;
    }

    private static List<SplitShare> holding(
            Settlement settlement, List<SplitShare> shares, SplitRole role, SettlementLine line) {
        List<SplitShare> holders =
                shares.stream().filter(share -> share.role() == role).toList();
        if (holders.isEmpty()) {
            throw new SettlementError(settlement.number() + ": the lease puts " + line.code() + " on the "
                    + role.value() + " and nobody on the split is one");
        }
        return holders;
    }

    /** Net of several statements, for a cheque that covers more than one. */
    public static BigDecimal combine(List<Settlement> settlements) {
        return Money.total(settlements.stream().map(Settlement::net).toList());
    }
}
