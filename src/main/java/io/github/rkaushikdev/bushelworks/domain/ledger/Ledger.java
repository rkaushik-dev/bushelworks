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
     * The chart a country elevator of this size actually runs on.
     *
     * <p>Short, and deliberately so. The headings exist because the year end is read by heading, and
     * the leaves exist because somebody eventually asks what the dryer earned as against what the gas
     * cost.
     */
    public static ChartOfAccounts standardChart() {
        return new ChartOfAccounts(List.of(
                Account.of("1000", "Assets", AccountType.ASSET),
                Account.of("1100", "Cash in bank", AccountType.ASSET).withParent("1000"),
                Account.of("1200", "Grain inventory", AccountType.ASSET).withParent("1000"),
                Account.of("1210", "Grain inventory, company owned", AccountType.ASSET)
                        .withParent("1200"),
                Account.of("1220", "Grain inventory, unpriced", AccountType.ASSET)
                        .withParent("1200"),
                Account.of("1300", "Advances to producers", AccountType.ASSET).withParent("1000"),
                Account.of("1400", "Accounts receivable", AccountType.ASSET).withParent("1000"),
                Account.of("2000", "Liabilities", AccountType.LIABILITY),
                Account.of("2100", "Grain payable to producers", AccountType.LIABILITY)
                        .withParent("2000"),
                Account.of("2200", "Checkoff payable", AccountType.LIABILITY).withParent("2000"),
                Account.of("2300", "Deferred payment contracts", AccountType.LIABILITY)
                        .withParent("2000"),
                Account.of("2400", "Storage billed in advance", AccountType.LIABILITY)
                        .withParent("2000"),
                Account.of("3000", "Equity", AccountType.EQUITY),
                Account.of("3100", "Retained earnings", AccountType.EQUITY).withParent("3000"),
                Account.of("4000", "Revenue", AccountType.REVENUE),
                Account.of("4100", "Storage revenue", AccountType.REVENUE).withParent("4000"),
                Account.of("4200", "Drying revenue", AccountType.REVENUE).withParent("4000"),
                Account.of("4300", "Handling and service", AccountType.REVENUE).withParent("4000"),
                Account.of("4400", "Grain margin", AccountType.REVENUE).withParent("4000"),
                Account.of("4500", "Demurrage recovered", AccountType.REVENUE).withParent("4000"),
                Account.of("5000", "Expense", AccountType.EXPENSE),
                Account.of("5100", "Freight paid", AccountType.EXPENSE).withParent("5000"),
                Account.of("5200", "Dryer fuel", AccountType.EXPENSE).withParent("5000"),
                Account.of("5300", "Shrink and handling loss", AccountType.EXPENSE)
                        .withParent("5000"),
                Account.of("5400", "Interest on advances", AccountType.EXPENSE).withParent("5000")));
    }
}
