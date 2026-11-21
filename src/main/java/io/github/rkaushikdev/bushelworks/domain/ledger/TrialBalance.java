package io.github.rkaushikdev.bushelworks.domain.ledger;

import io.github.rkaushikdev.bushelworks.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * What the accounts add up to over a span of days, one row per postable account in code order.
 *
 * @param through the last day included
 * @param rows the accounts, headings excluded
 */
public record TrialBalance(LocalDate through, List<TrialBalanceRow> rows) {

    public TrialBalance {
        Objects.requireNonNull(through, "through");
        rows = List.copyOf(rows);
    }

    public BigDecimal debits() {
        return Money.total(rows.stream().map(TrialBalanceRow::debits).toList());
    }

    public BigDecimal credits() {
        return Money.total(rows.stream().map(TrialBalanceRow::credits).toList());
    }

    public boolean balances() {
        return debits().compareTo(credits()) == 0;
    }

    /**
     * The row for one account.
     *
     * @throws AccountNotFound when the account is not on this trial balance, a heading included
     */
    public TrialBalanceRow row(String code) {
        for (TrialBalanceRow row : rows) {
            if (row.account().code().equals(code)) {
                return row;
            }
        }
        throw new AccountNotFound(code + " is not on this trial balance");
    }

    public BigDecimal balanceOf(String code) {
        return row(code).balance();
    }

    /** The balances of every account of one kind, added up. */
    public BigDecimal subtotal(AccountType type) {
        return Money.total(rows.stream()
                .filter(row -> row.account().type() == type)
                .map(TrialBalanceRow::balance)
                .toList());
    }

    /** The rows something landed in. */
    public List<TrialBalanceRow> nonempty() {
        return rows.stream().filter(row -> !row.isEmpty()).toList();
    }
}
