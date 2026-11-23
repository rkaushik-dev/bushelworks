package io.github.rkaushikdev.bushelworks.reports.patronage;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * One member's patronage refund, split between the cheque and the equity the co-op keeps.
 *
 * <p>A refund paid on a qualified written notice of allocation is income to the member the year it
 * is allocated, whether or not he sees the money, which is why the tax law will not let it count as
 * qualified unless at least a fifth of it goes out in cash. A nonqualified notice has no such floor:
 * the member is taxed when the retained part is finally redeemed, and the co-op is taxed on it now.
 *
 * @param partyCode the member
 * @param notice which kind of notice the allocation goes out on
 * @param allocated the member's whole share of the pool
 * @param cash what goes out on the cheque now
 * @param retained what the co-op keeps as the member's equity
 */
public record PatronageRefund(
        String partyCode, NoticeKind notice, BigDecimal allocated, BigDecimal cash, BigDecimal retained) {

    /** The smallest part of a qualified allocation that has to be paid in cash, as a percent. */
    public static final BigDecimal QUALIFIED_CASH_PERCENT = new BigDecimal("20");

    public PatronageRefund {
        Objects.requireNonNull(partyCode, "partyCode");
        Objects.requireNonNull(notice, "notice");
        if (Money.amount(cash.add(retained)).compareTo(Money.amount(allocated)) != 0) {
            throw new PatronageError(partyCode + ": cash " + cash + " and retained " + retained + " do not make the "
                    + allocated + " allocated");
        }
    }

    /**
     * Split an allocation at {@code cashPercent} in cash, the cents rounded in the cheque's favour.
     *
     * @throws PatronageError when a qualified notice would pay out less than a fifth in cash, or the
     *     percent is not a percent
     */
    public static PatronageRefund of(
            String partyCode, NoticeKind notice, BigDecimal allocated, BigDecimal cashPercent) {
        if (cashPercent.signum() < 0 || cashPercent.compareTo(Decimals.HUNDRED) > 0) {
            throw new PatronageError(cashPercent + "% is not a share of the refund");
        }
        if (notice == NoticeKind.QUALIFIED && cashPercent.compareTo(QUALIFIED_CASH_PERCENT) < 0) {
            throw new PatronageError(partyCode + ": a qualified notice has to pay at least " + QUALIFIED_CASH_PERCENT
                    + "% in cash, not " + cashPercent + "%");
        }
        BigDecimal whole = Money.amount(allocated);
        BigDecimal cash = Decimals.quantize(
                Decimals.divide(whole.multiply(cashPercent), Decimals.HUNDRED),
                Money.AMOUNT_PLACES,
                RoundingMode.CEILING);
        return new PatronageRefund(partyCode, notice, whole, cash, Money.amount(whole.subtract(cash)));
    }
}
