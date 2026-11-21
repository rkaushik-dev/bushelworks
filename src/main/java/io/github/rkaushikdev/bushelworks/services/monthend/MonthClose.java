package io.github.rkaushikdev.bushelworks.services.monthend;

import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.domain.ledger.AccountType;
import io.github.rkaushikdev.bushelworks.domain.ledger.TrialBalance;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What reviewing or closing a month found.
 *
 * <p>The review fills this in check by check, noting everything outstanding as it goes, and hands it
 * back whole. A month with nothing outstanding is clean.
 */
public final class MonthClose {

    private final String code;
    private final LocalDate startsOn;
    private final LocalDate endsOn;
    private int settlements;
    private List<String> unposted = List.of();
    private int entries;
    private TrialBalance balance;
    private final List<Outstanding> outstanding = new ArrayList<>();
    private boolean closed;

    /** A month as it stands before any check has run: nothing counted, nothing outstanding, open. */
    public MonthClose(String code, LocalDate startsOn, LocalDate endsOn) {
        this.code = Objects.requireNonNull(code, "code");
        this.startsOn = Objects.requireNonNull(startsOn, "startsOn");
        this.endsOn = Objects.requireNonNull(endsOn, "endsOn");
    }

    /** The period's code, {@code 2024-10}. */
    public String code() {
        return code;
    }

    public LocalDate startsOn() {
        return startsOn;
    }

    public LocalDate endsOn() {
        return endsOn;
    }

    /** The live statements dated in the month. */
    public int settlements() {
        return settlements;
    }

    void setSettlements(int value) {
        settlements = value;
    }

    /** The numbers of the month's statements that never reached the journal, in number order. */
    public List<String> unposted() {
        return unposted;
    }

    void setUnposted(List<String> value) {
        unposted = List.copyOf(value);
    }

    /** The journal entries posted in the month. */
    public int entries() {
        return entries;
    }

    void setEntries(int value) {
        entries = value;
    }

    /** The month's trial balance; empty until the review has got that far. */
    public Optional<TrialBalance> balance() {
        return Optional.ofNullable(balance);
    }

    void setBalance(TrialBalance value) {
        balance = value;
    }

    /** Everything standing in the way, in the order it was found. */
    public List<Outstanding> outstanding() {
        return List.copyOf(outstanding);
    }

    /** Whether the month is closed, now or before this review. */
    public boolean isClosed() {
        return closed;
    }

    void setClosed(boolean value) {
        closed = value;
    }

    /** Whether nothing stands in the way. */
    public boolean isClean() {
        return outstanding.isEmpty();
    }

    void note(String kind, String detail) {
        outstanding.add(new Outstanding(kind, detail));
    }

    /** What the revenue accounts took in over the month; nothing before there is a balance. */
    public BigDecimal revenue() {
        return balance().map(found -> found.subtotal(AccountType.REVENUE)).orElse(Money.ZERO_AMOUNT);
    }

    /** What the expense accounts paid out over the month; nothing before there is a balance. */
    public BigDecimal expense() {
        return balance().map(found -> found.subtotal(AccountType.EXPENSE)).orElse(Money.ZERO_AMOUNT);
    }

    /** Revenue less expense. */
    public BigDecimal margin() {
        return Money.amount(revenue().subtract(expense()));
    }
}
