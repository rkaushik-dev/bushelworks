package io.github.rkaushikdev.bushelworks.domain.ledger;

import io.github.rkaushikdev.bushelworks.Money;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * One side of one entry: an account, a side and a positive amount, rounded to cents on the way in.
 *
 * @param accountCode the account the leg posts to
 * @param side debit or credit
 * @param dollars the amount, always positive
 * @param memo anything written against the leg
 */
public record Leg(String accountCode, Side side, BigDecimal dollars, String memo) {

    public Leg {
        Objects.requireNonNull(accountCode, "accountCode");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(memo, "memo");
        if (dollars.signum() <= 0) {
            throw new LedgerError(accountCode + ": a leg carries a positive amount and a side, not " + dollars);
        }
        dollars = Money.amount(dollars);
    }

    /** A leg with no memo. */
    public Leg(String accountCode, Side side, BigDecimal dollars) {
        this(accountCode, side, dollars, "");
    }

    /** What this leg does to the account balance, in its natural direction. */
    public BigDecimal effectOn(Account account) {
        if (side == account.normalSide()) {
            return Money.amount(dollars);
        }
        return Money.amount(dollars.negate());
    }
}
