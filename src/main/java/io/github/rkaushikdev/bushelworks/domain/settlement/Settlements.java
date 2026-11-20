package io.github.rkaushikdev.bushelworks.domain.settlement;

import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.domain.tickets.SplitShare;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Splitting a statement among the parties on a load, and adding statements up. */
public final class Settlements {

    private Settlements() {}

    /**
     * Divide a finished net across the parties on the ticket, in split order.
     *
     * <p>Every line is split rather than only the net, so a rounding cent lands on the same party for
     * the gross and for the discounts and the two do not drift apart on the printed statements.
     */
    public static Map<String, BigDecimal> split(Settlement settlement, List<SplitShare> shares) {
        if (shares.isEmpty()) {
            throw new SettlementError(settlement.number() + ": nothing to split across");
        }
        List<BigDecimal> weights = shares.stream().map(SplitShare::percent).toList();
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        shares.forEach(share -> totals.put(share.partyId(), Money.ZERO_AMOUNT));
        for (SettlementLine line : settlement.lines()) {
            List<BigDecimal> parts = Money.allocate(line.signed(), weights);
            for (int index = 0; index < shares.size(); index++) {
                String party = shares.get(index).partyId();
                totals.put(party, Money.amount(totals.get(party).add(parts.get(index))));
            }
        }
        return totals;
    }

    /** Net of several statements, for a cheque that covers more than one. */
    public static BigDecimal combine(List<Settlement> settlements) {
        return Money.total(settlements.stream().map(Settlement::net).toList());
    }
}
