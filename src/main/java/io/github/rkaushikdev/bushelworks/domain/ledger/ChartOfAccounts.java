package io.github.rkaushikdev.bushelworks.domain.ledger;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.FiledSheet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The accounts a house has filed, and which of them will take a posting.
 *
 * <p>An account with children is a heading rather than a place to put money. That sounds like
 * bookkeeping etiquette until somebody posts a settlement to {@code 4000 revenue} instead of {@code
 * 4100 storage revenue} and the storage line on the year end comes out empty while the total is
 * right. Iterating a chart gives its accounts in code order.
 */
public final class ChartOfAccounts implements Iterable<Account> {

    private final Map<String, Account> accounts = new TreeMap<>();
    private final Map<String, List<String>> children = new HashMap<>();

    /** An empty chart. */
    public ChartOfAccounts() {}

    /** A chart of {@code accounts}, each parent filed before its children. */
    public ChartOfAccounts(Iterable<Account> accounts) {
        for (Account account : accounts) {
            add(account);
        }
    }

    /**
     * The chart a filed sheet describes, a row an account with the columns between bars.
     *
     * <pre>
     *     code | name | type | under | closes to
     * </pre>
     *
     * <p>A dash under {@code under} is a top heading and a dash under {@code closes to} closes to
     * nothing in particular. A parent has to be on a line above its children, and a code has to start
     * with the digit its type is numbered in.
     *
     * @throws LedgerError naming the line, when a row cannot be read or is numbered outside its type
     */
    public static ChartOfAccounts parse(String sheet) {
        ChartOfAccounts chart = new ChartOfAccounts();
        for (String line : FiledSheet.rows(sheet, "code")) {
            String[] cells = FiledSheet.barred(line);
            if (cells.length != 5) {
                throw new LedgerError("an account needs 5 columns, not " + cells.length + ": " + line);
            }
            AccountType type = AccountType.of(cells[2]);
            if (cells[0].isEmpty() || cells[0].charAt(0) != type.leadingDigit()) {
                throw new LedgerError(cells[0] + " is numbered outside the " + type.value()
                        + " accounts, which start with " + type.leadingDigit() + ": " + line);
            }
            Account account = Account.of(cells[0], cells[1], type);
            if (!cells[3].equals(FiledSheet.NOTHING)) {
                account = account.withParent(cells[3]);
            }
            if (!cells[4].equals(FiledSheet.NOTHING)) {
                account = account.withClosesTo(cells[4]);
            }
            chart.add(account);
        }
        return chart;
    }

    /**
     * File one more account.
     *
     * @throws ConflictError when the code is already in the chart
     * @throws AccountNotFound when its parent is not in the chart yet
     * @throws LedgerError when it is a different kind of account from its parent
     */
    public void add(Account account) {
        if (accounts.containsKey(account.code())) {
            throw new ConflictError("account " + account.code() + " is already in the chart");
        }
        if (account.parent().isPresent()) {
            String parentCode = account.parent().get();
            Account parent = accounts.get(parentCode);
            if (parent == null) {
                throw new AccountNotFound(account.code() + ": parent " + parentCode + " is not in the chart yet");
            }
            if (parent.type() != account.type()) {
                throw new LedgerError(account.code() + " is a " + account.type().value() + " under " + parent.code()
                        + ", which is a " + parent.type().value());
            }
            children.computeIfAbsent(parentCode, key -> new ArrayList<>()).add(account.code());
        }
        accounts.put(account.code(), account);
    }

    public boolean contains(String code) {
        return accounts.containsKey(code);
    }

    public int size() {
        return accounts.size();
    }

    @Override
    public Iterator<Account> iterator() {
        return inCodeOrder().iterator();
    }

    /**
     * The account filed under {@code code}.
     *
     * @throws AccountNotFound when there is none
     */
    public Account get(String code) {
        Account account = accounts.get(code);
        if (account == null) {
            throw new AccountNotFound("no account " + code + " in the chart");
        }
        return account;
    }

    /** The accounts filed directly under {@code code}, in code order. */
    public List<Account> childrenOf(String code) {
        get(code);
        return children.getOrDefault(code, List.of()).stream()
                .sorted()
                .map(accounts::get)
                .toList();
    }

    /** An account with children is a heading and holds no postings itself. */
    public boolean isHeading(String code) {
        get(code);
        return !children.getOrDefault(code, List.of()).isEmpty();
    }

    /**
     * The account, if it is one a leg may name.
     *
     * @throws LedgerError naming the sub accounts to use instead, when it is a heading
     */
    public Account postable(String code) {
        Account account = get(code);
        if (isHeading(code)) {
            String instead = childrenOf(code).stream().map(Account::code).collect(Collectors.joining(", "));
            throw new LedgerError(
                    code + " " + account.name() + " has sub accounts, so post to one of " + instead + " instead");
        }
        return account;
    }

    public List<Account> inCodeOrder() {
        return List.copyOf(accounts.values());
    }

    public List<Account> ofType(AccountType type) {
        return accounts.values().stream()
                .filter(account -> account.type() == type)
                .toList();
    }

    /** The account and every heading above it, the top heading first and the account last. */
    public List<Account> lineage(String code) {
        List<Account> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Optional<String> current = Optional.of(code);
        while (current.isPresent()) {
            String step = current.get();
            if (!seen.add(step)) {
                throw new LedgerError(code + ": the chart loops back on " + step);
            }
            Account account = get(step);
            chain.add(account);
            current = account.parent();
        }
        Collections.reverse(chain);
        return List.copyOf(chain);
    }
}
