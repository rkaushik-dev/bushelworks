package io.github.rkaushikdev.bushelworks.domain.ledger;

import java.util.Objects;
import java.util.Optional;

/**
 * One line in the chart.
 *
 * <p>{@code parent} is a code, not an account, because the chart is filed as a flat list and read
 * back in whatever order the file happens to be in. Parents are resolved as accounts are added to a
 * {@link ChartOfAccounts}, so a chart cannot be built out of order, and the office does add a sub
 * account below one that is already there.
 *
 * @param code the account code, with nothing around it
 * @param name what the account is called
 * @param type the kind of account
 * @param parent the heading it sits under, if any
 * @param closesTo the account its balance closes to at year end, if it names one
 */
public record Account(String code, String name, AccountType type, Optional<String> parent, Optional<String> closesTo) {

    public Account {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(closesTo, "closesTo");
        if (code.isEmpty() || !code.strip().equals(code)) {
            throw new LedgerError("account code '" + code + "' has to be a bare code");
        }
        if (name.isBlank()) {
            throw new LedgerError(code + ": an account needs a name");
        }
        if (parent.isPresent() && parent.get().equals(code)) {
            throw new LedgerError(code + ": an account cannot be its own parent");
        }
    }

    /** A top level account that closes to nothing in particular. */
    public static Account of(String code, String name, AccountType type) {
        return new Account(code, name, type, Optional.empty(), Optional.empty());
    }

    /** The same account filed under {@code parentCode}. */
    public Account withParent(String parentCode) {
        return new Account(code, name, type, Optional.of(parentCode), closesTo);
    }

    /** The same account, closing to {@code accountCode} at year end. */
    public Account withClosesTo(String accountCode) {
        return new Account(code, name, type, parent, Optional.of(accountCode));
    }

    /** The side that increases this account. */
    public Side normalSide() {
        return type.normalSide();
    }
}
