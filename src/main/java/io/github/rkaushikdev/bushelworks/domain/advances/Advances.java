package io.github.rkaushikdev.bushelworks.domain.advances;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** The cap on a settlement advance, and recovering advances out of a settlement. */
public final class Advances {

    /**
     * How much of a load's value, in percent, the house will advance against grain it holds. Above
     * this the house is lending against a price it has not agreed.
     */
    public static final BigDecimal SETTLEMENT_ADVANCE_CAP = new BigDecimal("80");

    private Advances() {}

    /** The most the house will advance against grain worth {@code value}. */
    public static BigDecimal capFor(BigDecimal value) {
        return capFor(value, SETTLEMENT_ADVANCE_CAP);
    }

    /** The most the house will advance, at {@code percent} of {@code value}. */
    public static BigDecimal capFor(BigDecimal value, BigDecimal percent) {
        if (value.signum() < 0) {
            throw new AdvanceError("cannot advance against a negative value");
        }
        return Money.amount(Decimals.divide(value.multiply(percent), Decimals.HUNDRED));
    }

    public static void checkSettlementAdvance(BigDecimal principal, BigDecimal grainValue) {
        checkSettlementAdvance(principal, grainValue, SETTLEMENT_ADVANCE_CAP);
    }

    /** Refuse an advance above the cap, naming both figures. */
    public static void checkSettlementAdvance(BigDecimal principal, BigDecimal grainValue, BigDecimal percent) {
        BigDecimal cap = capFor(grainValue, percent);
        if (Money.amount(principal).compareTo(cap) > 0) {
            throw new AdvanceError(
                    "an advance of " + Money.amount(principal) + " is above the " + percent + "% cap of " + cap);
        }
    }

    public static BigDecimal outstandingFor(List<Advance> advances) {
        return Money.total(advances.stream().map(Advance::outstanding).toList());
    }

    /**
     * What a recovery left: the advances as they stand afterwards, oldest first, and what is left of
     * the net.
     *
     * @param advances the advances after the recovery
     * @param remaining the net left for the producer
     */
    public record Recovery(List<Advance> advances, BigDecimal remaining) {

        public Recovery {
            advances = List.copyOf(advances);
        }
    }

    /**
     * Recover advances out of one settlement, oldest first, because an advance that has been out
     * longest is the one the lender asks about.
     */
    public static Recovery applyToSettlement(
            List<Advance> advances, BigDecimal net, String settlementNumber, LocalDate on) {
        BigDecimal remaining = Money.amount(net);
        if (remaining.signum() < 0) {
            // A settlement in debit recovers nothing and does not deepen the hole.
            remaining = Money.amount("0.00");
        }
        List<Advance> updated = new ArrayList<>();
        List<Advance> ordered = advances.stream()
                .sorted(Comparator.comparing(Advance::drawnOn).thenComparing(Advance::reference))
                .toList();
        for (Advance advance : ordered) {
            if (advance.isSettled() || remaining.signum() <= 0) {
                updated.add(advance);
                continue;
            }
            BigDecimal take = advance.recoverableFrom(remaining, on);
            if (take.signum() <= 0) {
                updated.add(advance);
                continue;
            }
            updated.add(advance.repay(settlementNumber, take, on));
            remaining = Money.amount(remaining.subtract(take));
        }
        return new Recovery(updated, remaining);
    }
}
