package io.github.rkaushikdev.bushelworks.services.closing;

import static io.github.rkaushikdev.bushelworks.HouseLog.field;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.HouseLog;
import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.domain.ledger.Account;
import io.github.rkaushikdev.bushelworks.domain.ledger.AccountType;
import io.github.rkaushikdev.bushelworks.domain.ledger.ChartOfAccounts;
import io.github.rkaushikdev.bushelworks.domain.ledger.JournalEntry;
import io.github.rkaushikdev.bushelworks.domain.ledger.Ledger;
import io.github.rkaushikdev.bushelworks.domain.ledger.Leg;
import io.github.rkaushikdev.bushelworks.domain.ledger.Side;
import io.github.rkaushikdev.bushelworks.domain.ledger.TrialBalance;
import io.github.rkaushikdev.bushelworks.domain.ledger.TrialBalanceRow;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.repositories.PeriodNotFound;
import io.github.rkaushikdev.bushelworks.reports.Columns;
import io.github.rkaushikdev.bushelworks.services.monthend.AccountingMonth;
import io.github.rkaushikdev.bushelworks.services.monthend.MonthEnd;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.slf4j.Logger;

/**
 * Closing the year.
 *
 * <p>Revenue and expense accounts measure a year. On the first day of the next one they have to read
 * nothing, or the second year's income statement shows the first year's margin on top of its own.
 * Closing is the entry that empties them into equity, and it is the only entry in the books that is
 * not raised by a document.
 *
 * <p>It is built out of the trial balance rather than out of the journal, which matters when a house
 * has reopened a month and posted into it: the balance is what the accounts say now, not what they
 * said when each entry went in.
 *
 * <p>An account's {@link Account#closesTo() closes to} setting is what makes this worth having on its
 * own. Most houses send everything to retained earnings, but a co-op sends the margin it earned on
 * member business to a patronage account and the rest to retained earnings, and those two numbers
 * are read off different accounts by different people. An account that names a destination goes
 * there; everything else goes to the default, and each destination gets its own leg so the entry
 * says which is which.
 */
public final class Closing {

    /** The source the closing entry carries; its reference is the year. */
    public static final String SOURCE = "year-end";

    /** Where a margin goes when no account says otherwise. */
    public static final String RETAINED_EARNINGS = "3100";

    /** The house named on the minute book page when nobody names another. */
    public static final String HOUSE_NAME = "BUSHELWORKS ELEVATOR";

    private static final Logger LOG = HouseLog.getLogger("closing");

    private static final int WIDTH = 72;

    private static final int FIRST_YEAR = 2000;

    private static final int LAST_YEAR = 2100;

    /** How many open months or outstanding items a message names before it stops. */
    private static final int LISTED = 5;

    /** The first day the journal can hold, so a running total starts before anything was posted. */
    private static final LocalDate BEGINNING = LocalDate.of(1, 1, 1);

    private Closing() {}

    /** The entry that empties every revenue and expense account into retained earnings. */
    public static JournalEntry closingEntry(ChartOfAccounts chart, TrialBalance balance, String number, LocalDate on) {
        return closingEntry(chart, balance, number, on, RETAINED_EARNINGS);
    }

    /**
     * The entry that empties every revenue and expense account.
     *
     * <p>Each account is posted on the side opposite the one it accumulated on, for exactly what it
     * holds, so it reads zero afterwards. A contra balance, which is a revenue account sitting on the
     * debit side because it took more reversals than sales, is closed the other way round rather than
     * skipped. Each destination then takes one leg for its share of the margin, in code order.
     *
     * @param retained where an account that names no destination closes to
     * @throws ClosingError when no revenue or expense account carries a balance
     * @throws io.github.rkaushikdev.bushelworks.domain.ledger.LedgerError when a destination is a
     *     heading
     */
    public static JournalEntry closingEntry(
            ChartOfAccounts chart, TrialBalance balance, String number, LocalDate on, String retained) {
        chart.postable(retained);
        List<Leg> legs = new ArrayList<>();
        Map<String, BigDecimal> owed = new TreeMap<>();

        for (TrialBalanceRow row : balance.rows()) {
            Account account = row.account();
            if (!closesOut(account)) {
                continue;
            }
            BigDecimal held = row.balance();
            if (held.signum() == 0) {
                continue;
            }
            Side side = held.signum() > 0 ? account.normalSide().opposite() : account.normalSide();
            legs.add(new Leg(account.code(), side, held.abs(), "close " + account.code()));
            String target = closesTo(chart, account.code(), retained);
            BigDecimal contribution = account.type() == AccountType.REVENUE ? held : held.negate();
            owed.merge(target, Money.amount(contribution), (sum, more) -> Money.amount(sum.add(more)));
        }

        if (legs.isEmpty()) {
            throw new ClosingError(
                    "nothing to close for " + on.getYear() + ": no revenue or expense account carries a balance");
        }

        for (Map.Entry<String, BigDecimal> share : owed.entrySet()) {
            if (share.getValue().signum() == 0) {
                continue;
            }
            chart.postable(share.getKey());
            legs.add(new Leg(
                    share.getKey(),
                    share.getValue().signum() > 0 ? Side.CREDIT : Side.DEBIT,
                    share.getValue().abs(),
                    "margin closed to " + share.getKey()));
        }

        JournalEntry entry = new JournalEntry(
                number, on, "year end close " + on.getYear(), legs, SOURCE, Integer.toString(on.getYear()));
        entry.checkBalances();
        return entry;
    }

    /** Work out what closing a year into retained earnings would post, and what is in the way. */
    public static YearClose reviewYear(UnitOfWork uow, int year) {
        return reviewYear(uow, year, RETAINED_EARNINGS);
    }

    /**
     * Work out what closing a year would post, and what is in the way.
     *
     * <p>Every month of the year has to be closed, a month not on file counting against it, and the
     * year must not have been closed already. A year with no revenue and no expense has no closing
     * entry.
     *
     * @param retained where an account that names no destination closes to
     * @throws ClosingError when the year is before 2000 or after 2100
     */
    public static YearClose reviewYear(UnitOfWork uow, int year, String retained) {
        if (year < FIRST_YEAR || year > LAST_YEAR) {
            throw new ClosingError("implausible accounting year " + year);
        }
        YearClose found = new YearClose(year);

        List<String> openMonths = new ArrayList<>();
        for (AccountingMonth month : MonthEnd.monthsOf(year)) {
            if (!isClosed(uow, month.code(), found)) {
                openMonths.add(month.code());
            }
        }
        if (!openMonths.isEmpty()) {
            found.note(openMonths.size() + " months are still open: "
                    + String.join(", ", openMonths.subList(0, Math.min(LISTED, openMonths.size()))));
        }

        if (uow.ledger().isPosted(SOURCE, Integer.toString(year))) {
            found.note(year + " has already been closed");
            found.setClosed(true);
        }

        ChartOfAccounts chart = uow.ledger().chart();
        LocalDate opens = LocalDate.of(year, 1, 1);
        LocalDate closes = LocalDate.of(year, 12, 31);
        List<JournalEntry> entries = uow.ledger().entriesBetween(opens, closes);
        TrialBalance balance = Ledger.trialBalance(chart, entries, closes, opens);
        if (!balance.balances()) {
            found.note(
                    "the year posts " + balance.debits() + " of debits against " + balance.credits() + " of credits");
        }

        found.setRevenue(balance.subtotal(AccountType.REVENUE));
        found.setExpense(balance.subtotal(AccountType.EXPENSE));
        if (found.margin().signum() == 0 && found.revenue().signum() == 0) {
            found.note(year + " has no revenue or expense on the books");
            return found;
        }

        JournalEntry entry = closingEntry(chart, balance, uow.ledger().nextNumber("Y", closes), closes, retained);
        found.setEntry(entry);
        found.setDestinations(destinations(chart, entry, retained));
        return found;
    }

    /** Post the closing entry into retained earnings, if the year is finished. */
    public static YearClose closeYear(UnitOfWork uow, int year, String closedBy) {
        return closeYear(uow, year, closedBy, null, false);
    }

    /**
     * Post the closing entry, if the year is finished. Logged as {@code year.closed}.
     *
     * @param retained where an account that names no destination closes to; null for {@link
     *     #RETAINED_EARNINGS}
     * @param force whether to close over whatever is outstanding
     * @throws ConflictError when the year has been closed already, or has something outstanding and
     *     {@code force} is not set
     * @throws ClosingError when there is nothing to close
     */
    public static YearClose closeYear(UnitOfWork uow, int year, String closedBy, String retained, boolean force) {
        YearClose found = reviewYear(uow, year, retained != null ? retained : RETAINED_EARNINGS);
        if (found.isClosed()) {
            throw new ConflictError(year + " has already been closed");
        }
        if (!found.isClean() && !force) {
            throw new ConflictError(year + " will not close: "
                    + String.join(
                            "; ",
                            found.outstanding()
                                    .subList(
                                            0,
                                            Math.min(LISTED, found.outstanding().size()))));
        }
        JournalEntry entry = found.entry().orElseThrow(() -> new ClosingError("nothing to close for " + year));

        entry.checkAgainst(uow.ledger().chart());
        uow.ledger().post(entry);
        found.setClosed(true);
        HouseLog.event(
                LOG,
                "year.closed",
                field("year", year),
                field("by", closedBy),
                field("entry", entry.number()),
                field("margin", found.margin().toString()));
        return found;
    }

    /** What the balance sheet accounts carry into a year, retained earnings always among them. */
    public static Map<String, BigDecimal> openingBalances(UnitOfWork uow, int year) {
        return openingBalances(uow, year, RETAINED_EARNINGS);
    }

    /**
     * What the balance sheet accounts carry into a year: every one with a balance at the end of the
     * year before, in code order, and the retained earnings account after them at zero when it carries
     * nothing.
     *
     * <p>Revenue and expense are not in here at all. If the year before was closed they are zero
     * anyway, and if it was not then carrying them forward would hide that nobody had closed it.
     */
    public static Map<String, BigDecimal> openingBalances(UnitOfWork uow, int year, String retained) {
        ChartOfAccounts chart = uow.ledger().chart();
        LocalDate closes = LocalDate.of(year - 1, 12, 31);
        List<JournalEntry> entries = uow.ledger().entriesBetween(BEGINNING, closes);
        TrialBalance balance = Ledger.trialBalance(chart, entries, closes);
        Map<String, BigDecimal> carried = new LinkedHashMap<>();
        for (TrialBalanceRow row : balance.rows()) {
            if (row.account().type().onBalanceSheet() && row.balance().signum() != 0) {
                carried.put(row.account().code(), row.balance());
            }
        }
        carried.putIfAbsent(retained, Money.ZERO_AMOUNT);
        return carried;
    }

    /** The year end as it goes in the minute book, for the house. */
    public static String render(YearClose found) {
        return render(found, HOUSE_NAME);
    }

    /** The year end as it goes in the minute book: the margin, where it lands, and what is outstanding. */
    public static String render(YearClose found, String houseName) {
        List<String> lines = new ArrayList<>();
        lines.add(Columns.centred(houseName, WIDTH));
        lines.add(Columns.centred("YEAR END " + found.year(), WIDTH));
        lines.add("=".repeat(WIDTH));
        lines.add(figure("revenue", found.revenue()));
        lines.add(figure("expense", found.expense()));
        lines.add(figure("margin", found.margin()));
        lines.add("-".repeat(WIDTH));
        for (Destination destination : found.destinations()) {
            lines.add(figure(destination.accountCode(), destination.dollars()));
        }
        lines.add("-".repeat(WIDTH));
        if (found.isClean()) {
            lines.add(found.isClosed() ? "closed" : "would close clean");
        } else {
            lines.add(found.outstanding().size() + " outstanding:");
            found.outstanding().forEach(item -> lines.add("  " + item));
        }
        return String.join("\n", lines);
    }

    /** Revenue or expense accounts still carrying a balance. */
    public static List<String> accountsLeftOpen(ChartOfAccounts chart, TrialBalance balance) {
        return accountsLeftOpen(chart, balance, List.of());
    }

    /**
     * Revenue or expense accounts still carrying a balance after a close, in code order.
     *
     * <p>Run against the trial balance for the day after the close. Anything in here is an account the
     * closing entry missed, which happens when somebody posts into a closed year.
     *
     * @param codes the accounts to look at; empty for every one
     */
    public static List<String> accountsLeftOpen(ChartOfAccounts chart, TrialBalance balance, Collection<String> codes) {
        Set<String> wanted = Set.copyOf(codes);
        return balance.rows().stream()
                .filter(row -> closesOut(row.account()))
                .filter(row -> row.balance().signum() != 0)
                .map(row -> row.account().code())
                .filter(code -> wanted.isEmpty() || wanted.contains(code))
                .toList();
    }

    private static boolean closesOut(Account account) {
        return account.type() == AccountType.REVENUE || account.type() == AccountType.EXPENSE;
    }

    /**
     * Where an account's balance goes, inheriting from its heading.
     *
     * <p>A house that sets the destination on the {@code 4000 Revenue} heading means it for everything
     * under it, and setting it on twelve leaves instead is twelve chances to miss one. The nearest
     * setting wins.
     */
    private static String closesTo(ChartOfAccounts chart, String code, String fallback) {
        List<Account> lineage = chart.lineage(code);
        for (int index = lineage.size() - 1; index >= 0; index--) {
            String named = lineage.get(index).closesTo().orElse("");
            if (!named.isEmpty()) {
                return named;
            }
        }
        return fallback;
    }

    private static List<Destination> destinations(ChartOfAccounts chart, JournalEntry entry, String retained) {
        Set<String> equity = new TreeSet<>();
        for (Leg leg : entry.legs()) {
            if (chart.get(leg.accountCode()).type() == AccountType.EQUITY) {
                equity.add(leg.accountCode());
            }
        }
        equity.add(retained);
        List<Destination> out = new ArrayList<>();
        for (String code : equity) {
            List<Leg> legs = entry.touching(code);
            if (legs.isEmpty()) {
                continue;
            }
            Leg leg = legs.get(0);
            BigDecimal dollars = leg.side() == Side.CREDIT
                    ? leg.dollars()
                    : Money.amount(leg.dollars().negate());
            List<String> from = entry.legs().stream()
                    .map(Leg::accountCode)
                    .filter(other -> !other.equals(code))
                    .filter(other -> closesTo(chart, other, retained).equals(code))
                    .sorted()
                    .toList();
            out.add(new Destination(code, dollars, from));
        }
        return out;
    }

    private static boolean isClosed(UnitOfWork uow, String code, YearClose found) {
        try {
            return uow.ledger().period(code).isClosed();
        } catch (PeriodNotFound missing) {
            found.note(code + " is not on file");
            return true;
        }
    }

    private static String figure(String label, BigDecimal value) {
        return Columns.left(label, 28) + Columns.right(value.toString(), 16);
    }
}
