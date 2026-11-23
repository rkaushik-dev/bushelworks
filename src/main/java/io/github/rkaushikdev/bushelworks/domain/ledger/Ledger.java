package io.github.rkaushikdev.bushelworks.domain.ledger;

import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.Ordered;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The general ledger the office keeps alongside the grain records.
 *
 * <p>Everything else in the package answers "how many bushels" and "what is the producer owed".
 * This answers "and what did that do to the books", which is a different question with a different
 * auditor asking it. A journal entry balances or it does not post, and an account that has children
 * is a heading rather than a place to put money.
 *
 * <p>Signs are not stored. A leg is a positive amount and a side, and what that does to the account
 * balance depends on which side the account normally carries. That is more ceremony than a signed
 * number, and it is the reason a debit to a revenue account reads as a reduction on the trial
 * balance instead of quietly adding.
 */
public final class Ledger {

    private Ledger() {}

    public static Leg debit(String accountCode, BigDecimal dollars) {
        return new Leg(accountCode, Side.DEBIT, dollars);
    }

    public static Leg debit(String accountCode, BigDecimal dollars, String memo) {
        return new Leg(accountCode, Side.DEBIT, dollars, memo);
    }

    public static Leg credit(String accountCode, BigDecimal dollars) {
        return new Leg(accountCode, Side.CREDIT, dollars);
    }

    public static Leg credit(String accountCode, BigDecimal dollars, String memo) {
        return new Leg(accountCode, Side.CREDIT, dollars, memo);
    }

    /** Every entry posted up to and including {@code through}, added up per account. */
    public static TrialBalance trialBalance(ChartOfAccounts chart, Iterable<JournalEntry> entries, LocalDate through) {
        return trialBalance(chart, entries, through, Optional.empty());
    }

    /** The entries posted from {@code since} through {@code through}, both included. */
    public static TrialBalance trialBalance(
            ChartOfAccounts chart, Iterable<JournalEntry> entries, LocalDate through, LocalDate since) {
        return trialBalance(chart, entries, through, Optional.of(since));
    }

    /**
     * Add the entries up per account, headings excluded.
     *
     * <p>Entries outside the window are skipped rather than refused, because the caller hands in the
     * whole journal and asks for a month. Without a {@code since} it is a running total.
     */
    public static TrialBalance trialBalance(
            ChartOfAccounts chart, Iterable<JournalEntry> entries, LocalDate through, Optional<LocalDate> since) {
        Map<String, BigDecimal> debits = new HashMap<>();
        Map<String, BigDecimal> credits = new HashMap<>();
        for (JournalEntry entry : entries) {
            if (entry.postedOn().isAfter(through)) {
                continue;
            }
            if (since.isPresent() && entry.postedOn().isBefore(since.get())) {
                continue;
            }
            for (Leg leg : entry.legs()) {
                Map<String, BigDecimal> bucket = leg.side() == Side.DEBIT ? debits : credits;
                bucket.merge(leg.accountCode(), leg.dollars(), (sum, more) -> Money.amount(sum.add(more)));
            }
        }
        List<TrialBalanceRow> rows = chart.inCodeOrder().stream()
                .filter(account -> !chart.isHeading(account.code()))
                .map(account -> new TrialBalanceRow(
                        account,
                        debits.getOrDefault(account.code(), Money.ZERO_AMOUNT),
                        credits.getOrDefault(account.code(), Money.ZERO_AMOUNT)))
                .toList();
        return new TrialBalance(through, rows);
    }

    /** Every account's balance with the headings filled in from below, in code order. */
    public static Map<String, BigDecimal> rollup(ChartOfAccounts chart, TrialBalance balance) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (Account account : chart) {
            totals.put(account.code(), Money.ZERO_AMOUNT);
        }
        for (TrialBalanceRow row : balance.rows()) {
            for (Account account : chart.lineage(row.account().code())) {
                totals.put(
                        account.code(), Money.amount(totals.get(account.code()).add(row.balance())));
            }
        }
        return Ordered.copyOf(totals);
    }

    /**
     * The chart a country elevator of this size actually runs on, as the accountant keeps it.
     *
     * <p>Short, and deliberately so. The headings exist because the year end is read by heading, and
     * the leaves exist because somebody eventually asks what the dryer earned as against what the gas
     * cost.
     */
    public static final String STANDARD_CHART =
            """
            code | name                           | type      | under | closes to
            1000 | Assets                         | asset     | -     | -
            1100 | Cash in bank                   | asset     | 1000  | -
            1200 | Grain inventory                | asset     | 1000  | -
            1210 | Grain inventory, company owned | asset     | 1200  | -
            1220 | Grain inventory, unpriced      | asset     | 1200  | -
            1300 | Advances to producers          | asset     | 1000  | -
            1400 | Accounts receivable            | asset     | 1000  | -
            2000 | Liabilities                    | liability | -     | -
            2100 | Grain payable to producers     | liability | 2000  | -
            2200 | Checkoff payable               | liability | 2000  | -
            2300 | Deferred payment contracts     | liability | 2000  | -
            2400 | Storage billed in advance      | liability | 2000  | -
            3000 | Equity                         | equity    | -     | -
            3100 | Retained earnings              | equity    | 3000  | -
            4000 | Revenue                        | revenue   | -     | -
            4100 | Storage revenue                | revenue   | 4000  | -
            4200 | Drying revenue                 | revenue   | 4000  | -
            4300 | Handling and service           | revenue   | 4000  | -
            4400 | Grain margin                   | revenue   | 4000  | -
            4500 | Demurrage recovered            | revenue   | 4000  | -
            5000 | Expense                        | expense   | -     | -
            5100 | Freight paid                   | expense   | 5000  | -
            5200 | Dryer fuel                     | expense   | 5000  | -
            5300 | Shrink and handling loss       | expense   | 5000  | -
            5400 | Interest on advances           | expense   | 5000  | -
            """;

    /** The standard chart, read off {@link #STANDARD_CHART}. */
    public static ChartOfAccounts standardChart() {
        return ChartOfAccounts.parse(STANDARD_CHART);
    }
}
