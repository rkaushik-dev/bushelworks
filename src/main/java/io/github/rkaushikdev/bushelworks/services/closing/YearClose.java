package io.github.rkaushikdev.bushelworks.services.closing;

import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.domain.ledger.JournalEntry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What reviewing or closing a year found.
 *
 * <p>The review fills this in as it goes: the year's revenue and expense, the entry that would close
 * them, where the margin lands, and everything standing in the way. A year with nothing outstanding
 * is clean.
 */
public final class YearClose {

    private final int year;
    private BigDecimal revenue = Money.ZERO_AMOUNT;
    private BigDecimal expense = Money.ZERO_AMOUNT;
    private List<Destination> destinations = List.of();
    private JournalEntry entry;
    private final List<String> outstanding = new ArrayList<>();
    private boolean closed;

    YearClose(int year) {
        this.year = year;
    }

    /** The accounting year. */
    public int year() {
        return year;
    }

    /** What the revenue accounts carry for the year. */
    public BigDecimal revenue() {
        return revenue;
    }

    void setRevenue(BigDecimal value) {
        revenue = value;
    }

    /** What the expense accounts carry for the year. */
    public BigDecimal expense() {
        return expense;
    }

    void setExpense(BigDecimal value) {
        expense = value;
    }

    /** Revenue less expense. */
    public BigDecimal margin() {
        return Money.amount(revenue.subtract(expense));
    }

    /** The equity accounts the margin lands in, in code order. */
    public List<Destination> destinations() {
        return destinations;
    }

    void setDestinations(List<Destination> value) {
        destinations = List.copyOf(value);
    }

    /** The closing entry; empty when there is nothing to close. */
    public Optional<JournalEntry> entry() {
        return Optional.ofNullable(entry);
    }

    void setEntry(JournalEntry value) {
        entry = value;
    }

    /** Everything standing in the way, in the order it was found. */
    public List<String> outstanding() {
        return List.copyOf(outstanding);
    }

    /** Whether the year is closed, now or before this review. */
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

    void note(String detail) {
        outstanding.add(detail);
    }
}
