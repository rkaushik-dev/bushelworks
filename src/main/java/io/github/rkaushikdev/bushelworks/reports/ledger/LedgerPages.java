package io.github.rkaushikdev.bushelworks.reports.ledger;

import static io.github.rkaushikdev.bushelworks.reports.Columns.clip;
import static io.github.rkaushikdev.bushelworks.reports.Columns.left;
import static io.github.rkaushikdev.bushelworks.reports.Columns.right;

import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.domain.ledger.Account;
import io.github.rkaushikdev.bushelworks.domain.ledger.AccountType;
import io.github.rkaushikdev.bushelworks.domain.ledger.ChartOfAccounts;
import io.github.rkaushikdev.bushelworks.domain.ledger.Ledger;
import io.github.rkaushikdev.bushelworks.domain.ledger.TrialBalance;
import io.github.rkaushikdev.bushelworks.domain.ledger.TrialBalanceRow;
import io.github.rkaushikdev.bushelworks.reports.Columns;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The books, printed.
 *
 * <p>Three pages the office actually asks for. The trial balance is the working one and the other
 * two are what the accountant is sent in January.
 *
 * <p>Everything here is eighty columns because the ledger printer is the same dot matrix the
 * statements come off, and a report that wraps is a report somebody retypes into a spreadsheet by
 * hand.
 *
 * <p>Headings are indented by their depth in the chart rather than by a fixed rule, so a house that
 * files sub accounts three deep gets a readable page without anybody changing this class.
 */
public final class LedgerPages {

    /** The printer's width. No line on any page is longer. */
    public static final int WIDTH = 80;

    /** The name the pages carry when the house gives none. */
    public static final String HOUSE_NAME = "BUSHELWORKS ELEVATOR";

    /** The trial balance file's header. */
    public static final String CSV_HEADER = "code,account,type,debits,credits,balance";

    private LedgerPages() {}

    /** Every account that was touched, its two columns, and the pair that has to agree. */
    public static String trialBalancePage(TrialBalance balance) {
        return trialBalancePage(balance, HOUSE_NAME, false);
    }

    /** The trial balance, with the untouched accounts too when {@code includeEmpty} is set. */
    public static String trialBalancePage(TrialBalance balance, boolean includeEmpty) {
        return trialBalancePage(balance, HOUSE_NAME, includeEmpty);
    }

    /**
     * Every account, its debits and credits as two unsigned columns, and the pair of totals that has
     * to agree. A pair that does not agree gets a line saying by how much.
     *
     * @param includeEmpty whether an account nothing was posted to gets a line
     */
    public static String trialBalancePage(TrialBalance balance, String houseName, boolean includeEmpty) {
        List<TrialBalanceRow> rows = includeEmpty ? balance.rows() : balance.nonempty();
        List<String> lines = header(houseName, "TRIAL BALANCE", "through " + balance.through());
        lines.add(left("code", 8) + left("account", 38) + right("debits", 16) + right("credits", 16));
        lines.add(rule('-'));
        for (TrialBalanceRow row : rows) {
            lines.add(left(row.account().code(), 8)
                    + left(clip(row.account().name(), 37), 38)
                    + right(money(row.debits()), 16)
                    + right(money(row.credits()), 16));
        }
        lines.add(rule('-'));
        lines.add(
                left("", 8) + left("", 38) + right(money(balance.debits()), 16) + right(money(balance.credits()), 16));
        if (!balance.balances()) {
            BigDecimal out = Money.amount(balance.debits().subtract(balance.credits()));
            lines.add("OUT OF BALANCE BY " + money(out));
        }
        return String.join("\n", lines);
    }

    /** What the house earned from {@code opens} to the balance's day, for the house. */
    public static String incomeStatement(ChartOfAccounts chart, TrialBalance balance, LocalDate opens) {
        return incomeStatement(chart, balance, opens, HOUSE_NAME);
    }

    /**
     * What the house earned in a span, revenue first then what it cost. A heading always prints; an
     * account under it prints only when something is in it.
     */
    public static String incomeStatement(
            ChartOfAccounts chart, TrialBalance balance, LocalDate opens, String houseName) {
        Map<String, BigDecimal> totals = Ledger.rollup(chart, balance);
        List<String> lines = header(houseName, "INCOME STATEMENT", opens + " to " + balance.through());
        BigDecimal revenue = balance.subtotal(AccountType.REVENUE);
        BigDecimal expense = balance.subtotal(AccountType.EXPENSE);

        section(lines, "REVENUE", AccountType.REVENUE, chart, totals);
        lines.add(rule('-'));
        section(lines, "EXPENSE", AccountType.EXPENSE, chart, totals);
        lines.add(rule('-'));

        lines.add(figure("total revenue", revenue));
        lines.add(figure("total expense", expense));
        lines.add(rule('='));
        lines.add(figure("margin", Money.amount(revenue.subtract(expense))));
        return String.join("\n", lines);
    }

    /** What the house owns and owes as at the balance's day, for the house. */
    public static String balanceSheet(ChartOfAccounts chart, TrialBalance balance) {
        return balanceSheet(chart, balance, HOUSE_NAME);
    }

    /**
     * What the house owns and what it owes, as at a day.
     *
     * <p>The margin for the period is shown as its own line under equity rather than folded into
     * retained earnings. Until the year is closed that money has not reached retained earnings, and
     * showing it there would say it had.
     */
    public static String balanceSheet(ChartOfAccounts chart, TrialBalance balance, String houseName) {
        Map<String, BigDecimal> totals = Ledger.rollup(chart, balance);
        List<String> lines = header(houseName, "BALANCE SHEET", "as at " + balance.through());
        BigDecimal assets = balance.subtotal(AccountType.ASSET);
        BigDecimal liabilities = balance.subtotal(AccountType.LIABILITY);
        BigDecimal equity = balance.subtotal(AccountType.EQUITY);
        BigDecimal margin =
                Money.amount(balance.subtotal(AccountType.REVENUE).subtract(balance.subtotal(AccountType.EXPENSE)));

        section(lines, "ASSETS", AccountType.ASSET, chart, totals);
        lines.add(rule('-'));
        section(lines, "LIABILITIES", AccountType.LIABILITY, chart, totals);
        lines.add(rule('-'));
        section(lines, "EQUITY", AccountType.EQUITY, chart, totals);
        lines.add(figure("  margin for the period", margin));
        lines.add(rule('-'));

        lines.add(figure("total assets", assets));
        BigDecimal claims = Money.amount(liabilities.add(equity).add(margin));
        lines.add(figure("liabilities and equity", claims));
        lines.add(rule('='));
        if (assets.compareTo(claims) != 0) {
            lines.add("OUT OF BALANCE BY " + money(Money.amount(assets.subtract(claims))));
        } else {
            lines.add(left("", 8) + left("in balance", 54));
        }
        return String.join("\n", lines);
    }

    /** The trial balance as a file the accounting package will take, touched accounts only. */
    public static String toCsv(TrialBalance balance) {
        return toCsv(balance, false);
    }

    /**
     * The trial balance as a file the accounting package will take.
     *
     * <p>Amounts are unformatted and unsigned per column, because the package reads the two columns
     * and works the sign out itself. Formatting them the way the printed page does is what broke the
     * first import. The account name is always quoted.
     */
    public static String toCsv(TrialBalance balance, boolean includeEmpty) {
        List<TrialBalanceRow> rows = includeEmpty ? balance.rows() : balance.nonempty();
        StringBuilder out = new StringBuilder(CSV_HEADER);
        for (TrialBalanceRow row : rows) {
            String name = row.account().name().replace("\"", "\"\"");
            out.append('\n')
                    .append(row.account().code())
                    .append(",\"")
                    .append(name)
                    .append("\",")
                    .append(row.account().type().value())
                    .append(',')
                    .append(row.debits())
                    .append(',')
                    .append(row.credits())
                    .append(',')
                    .append(row.balance());
        }
        return out.append('\n').toString();
    }

    /** Cents, grouped by thousands, with a trailing minus the way a ledger prints one. */
    static String money(BigDecimal value) {
        if (value.signum() < 0) {
            return grouped(value.negate()) + "-";
        }
        return grouped(value) + " ";
    }

    private static String grouped(BigDecimal value) {
        return String.format(Locale.ROOT, "%,.2f", value.setScale(2, RoundingMode.HALF_EVEN));
    }

    private static List<String> header(String houseName, String title, String subtitle) {
        List<String> lines = new ArrayList<>();
        lines.add(Columns.centred(houseName, WIDTH));
        lines.add(Columns.centred(title, WIDTH));
        lines.add(Columns.centred(subtitle, WIDTH));
        lines.add(rule('='));
        return lines;
    }

    /** A label, then every account of a type that is a heading or has something in it. */
    private static void section(
            List<String> lines, String label, AccountType type, ChartOfAccounts chart, Map<String, BigDecimal> totals) {
        lines.add(label);
        for (Account account : chart.ofType(type)) {
            BigDecimal value = totals.get(account.code());
            if (value.signum() == 0 && !chart.isHeading(account.code())) {
                continue;
            }
            int depth = chart.lineage(account.code()).size() - 1;
            String name = "  ".repeat(depth) + account.name();
            lines.add(left(account.code(), 8) + left(clip(name, 53), 54) + right(money(value), 18));
        }
    }

    private static String figure(String label, BigDecimal value) {
        return left("", 8) + left(label, 54) + right(money(value), 18);
    }

    private static String rule(char letter) {
        return String.valueOf(letter).repeat(WIDTH);
    }
}
