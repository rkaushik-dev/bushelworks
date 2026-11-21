package io.github.rkaushikdev.bushelworks.domain.ledger;

import io.github.rkaushikdev.bushelworks.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * A balanced set of legs, posted on a day, pointing back at its source.
 *
 * <p>An entry balances or it does not post. A wrong entry is corrected by posting its {@link
 * #reverse reversal}, never by editing it.
 *
 * @param number the entry number
 * @param postedOn the day it posts
 * @param memo what it is for
 * @param legs at least two legs
 * @param source what kind of document raised it, such as {@code settlement}
 * @param sourceReference which one
 */
public record JournalEntry(
        String number, LocalDate postedOn, String memo, List<Leg> legs, String source, String sourceReference) {

    public JournalEntry {
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(postedOn, "postedOn");
        Objects.requireNonNull(memo, "memo");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sourceReference, "sourceReference");
        if (legs.size() < 2) {
            throw new LedgerError(number + ": an entry needs at least two legs, got " + legs.size());
        }
        legs = List.copyOf(legs);
    }

    /** An entry that points back at no source document. */
    public JournalEntry(String number, LocalDate postedOn, String memo, List<Leg> legs) {
        this(number, postedOn, memo, legs, "", "");
    }

    public BigDecimal debits() {
        return sideTotal(Side.DEBIT);
    }

    public BigDecimal credits() {
        return sideTotal(Side.CREDIT);
    }

    private BigDecimal sideTotal(Side side) {
        return Money.total(legs.stream()
                .filter(leg -> leg.side() == side)
                .map(Leg::dollars)
                .toList());
    }

    public boolean balances() {
        return debits().compareTo(credits()) == 0;
    }

    /**
     * Refuse an entry whose debits and credits differ.
     *
     * @throws LedgerError saying what it is out by
     */
    public void checkBalances() {
        if (!balances()) {
            throw new LedgerError(number + ": debits " + debits() + " do not equal credits " + credits() + ", out by "
                    + Money.amount(debits().subtract(credits())));
        }
    }

    /** Balanced, and every leg names an account that will take a posting. */
    public void checkAgainst(ChartOfAccounts chart) {
        checkBalances();
        for (Leg leg : legs) {
            chart.postable(leg.accountCode());
        }
    }

    /** The legs that post to one account, in entry order. */
    public List<Leg> touching(String accountCode) {
        return legs.stream()
                .filter(leg -> leg.accountCode().equals(accountCode))
                .toList();
    }

    /** The same entry with every side flipped, memo {@code reversal of <number>}. */
    public JournalEntry reverse(String reversalNumber, LocalDate on) {
        return reverse(reversalNumber, on, "");
    }

    /**
     * The same entry with every side flipped.
     *
     * <p>Both stay in the journal and the pair nets to nothing. The reversal keeps the source it came
     * from; an empty memo becomes {@code reversal of <number>}.
     */
    public JournalEntry reverse(String reversalNumber, LocalDate on, String reversalMemo) {
        List<Leg> flipped = legs.stream()
                .map(leg -> new Leg(leg.accountCode(), leg.side().opposite(), leg.dollars(), leg.memo()))
                .toList();
        String text = reversalMemo.isEmpty() ? "reversal of " + number : reversalMemo;
        return new JournalEntry(reversalNumber, on, text, flipped, source, sourceReference);
    }
}
