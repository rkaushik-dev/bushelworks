package io.github.rkaushikdev.bushelworks.services.closing;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * One equity account and what the year end closes into it.
 *
 * @param accountCode the equity account
 * @param dollars what it takes, positive when it is credited and negative when a loss debits it
 * @param fromAccounts the accounts on the closing entry that close to it, in code order
 */
public record Destination(String accountCode, BigDecimal dollars, List<String> fromAccounts) {

    public Destination {
        Objects.requireNonNull(accountCode, "accountCode");
        Objects.requireNonNull(dollars, "dollars");
        fromAccounts = List.copyOf(fromAccounts);
    }
}
