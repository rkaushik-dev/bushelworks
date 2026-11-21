package io.github.rkaushikdev.bushelworks.domain.ledger;

import io.github.rkaushikdev.bushelworks.Money;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * One account's debits and credits over the span of a trial balance.
 *
 * @param account the account
 * @param debits everything debited to it
 * @param credits everything credited to it
 */
public record TrialBalanceRow(Account account, BigDecimal debits, BigDecimal credits) {

    public TrialBalanceRow {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(debits, "debits");
        Objects.requireNonNull(credits, "credits");
    }

    /** Positive means the account sits on its normal side. */
    public BigDecimal balance() {
        if (account.normalSide() == Side.DEBIT) {
            return Money.amount(debits.subtract(credits));
        }
        return Money.amount(credits.subtract(debits));
    }

    /** Nothing landed in the account at all. */
    public boolean isEmpty() {
        return debits.signum() == 0 && credits.signum() == 0;
    }
}
