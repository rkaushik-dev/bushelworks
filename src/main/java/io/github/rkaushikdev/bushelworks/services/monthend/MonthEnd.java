package io.github.rkaushikdev.bushelworks.services.monthend;

import static io.github.rkaushikdev.bushelworks.HouseLog.field;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.HouseLog;
import io.github.rkaushikdev.bushelworks.domain.ledger.Account;
import io.github.rkaushikdev.bushelworks.domain.ledger.AccountType;
import io.github.rkaushikdev.bushelworks.domain.ledger.ChartOfAccounts;
import io.github.rkaushikdev.bushelworks.domain.ledger.JournalEntry;
import io.github.rkaushikdev.bushelworks.domain.ledger.Ledger;
import io.github.rkaushikdev.bushelworks.domain.ledger.TrialBalance;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.PeriodRow;
import io.github.rkaushikdev.bushelworks.persistence.models.SettlementRow;
import io.github.rkaushikdev.bushelworks.persistence.repositories.PeriodNotFound;
import io.github.rkaushikdev.bushelworks.services.posting.Posting;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;

/**
 * Closing a month, and what has to be true before it will close.
 *
 * <p>The day close asks whether one day ties. This asks whether a month is finished, which is a
 * different question with a harder answer: a day can tie perfectly and still be missing a settlement
 * nobody has keyed yet.
 *
 * <p>Closing is one way. {@link #reopenPeriod} takes a reason and writes it down, because the
 * alternative is somebody editing the {@code closed} column directly and nobody ever finding out.
 *
 * <p>The three checks, in the order they are cheapest to fix:
 *
 * <ol>
 *   <li>every settlement dated in the month is in the journal
 *   <li>the month's entries balance
 *   <li>the month before this one is closed
 * </ol>
 *
 * <p>The third is the one people argue about. Closing March while February is open means the
 * February return goes in against figures that can still move, so the close refuses and says which
 * month to do first.
 */
public final class MonthEnd {

    private static final Logger LOG = HouseLog.getLogger("monthend");

    /** The earliest accounting year the house will file. */
    private static final int FIRST_YEAR = 2000;

    /** The latest accounting year the house will file. */
    private static final int LAST_YEAR = 2100;

    /** How many unposted statements or outstanding items a message names before it stops. */
    private static final int LISTED = 5;

    private MonthEnd() {}

    /** The twelve periods of a calendar year, January first. */
    public static List<AccountingMonth> monthsOf(int year) {
        List<AccountingMonth> months = new ArrayList<>(12);
        for (int month = 1; month <= 12; month++) {
            YearMonth span = YearMonth.of(year, month);
            months.add(new AccountingMonth(codeOf(year, month), span.atDay(1), span.atEndOfMonth()));
        }
        return List.copyOf(months);
    }

    /**
     * Put a year of open months on file.
     *
     * <p>Run once when the house is set up and once each December. A year that is already filed is a
     * conflict rather than a no-op, because the usual way to reach this twice is a script that ran
     * further than somebody meant it to. Logged as {@code periods.filed}.
     *
     * @throws MonthEndError when the year is before 2000 or after 2100
     * @throws ConflictError when any of its months is already on file
     */
    public static List<PeriodRow> fileYear(UnitOfWork uow, int year) {
        if (year < FIRST_YEAR || year > LAST_YEAR) {
            throw new MonthEndError("implausible accounting year " + year);
        }
        List<PeriodRow> rows = new ArrayList<>();
        for (AccountingMonth month : monthsOf(year)) {
            PeriodRow row = new PeriodRow();
            row.setCode(month.code());
            row.setStartsOn(month.startsOn());
            row.setEndsOn(month.endsOn());
            rows.add(row);
        }
        List<PeriodRow> filed = uow.ledger().addPeriods(rows);
        HouseLog.event(LOG, "periods.filed", field("year", year), field("months", filed.size()));
        return filed;
    }

    /**
     * Work out whether a month would close, without closing it.
     *
     * <p>A month already closed reports that and skips the sequence check.
     *
     * @throws PeriodNotFound when no month is filed under {@code code}
     */
    public static MonthClose review(UnitOfWork uow, String code) {
        PeriodRow period = uow.ledger().period(code);
        MonthClose found = new MonthClose(period.getCode(), period.getStartsOn(), period.getEndsOn());

        List<SettlementRow> settlements = uow.settlements().inWindow(period.getStartsOn(), period.getEndsOn());
        found.setSettlements(settlements.size());
        List<String> missing = settlements.stream()
                .map(SettlementRow::getNumber)
                .filter(number -> !uow.ledger().isPosted(Posting.SOURCE, number))
                .toList();
        found.setUnposted(missing);
        if (!missing.isEmpty()) {
            String listed = String.join(", ", missing.subList(0, Math.min(LISTED, missing.size())));
            found.note("unposted", missing.size() + " settlements are not in the journal: " + listed);
        }

        List<JournalEntry> entries = uow.ledger().entriesBetween(period.getStartsOn(), period.getEndsOn());
        found.setEntries(entries.size());
        TrialBalance balance =
                Ledger.trialBalance(uow.ledger().chart(), entries, period.getEndsOn(), period.getStartsOn());
        found.setBalance(balance);
        if (!balance.balances()) {
            found.note(
                    "journal",
                    "the month posts " + balance.debits() + " of debits against " + balance.credits() + " of credits");
        }

        if (period.isClosed()) {
            found.note(
                    "closed",
                    period.getCode() + " was closed on "
                            + period.getClosedOn().map(LocalDate::toString).orElse("None"));
            found.setClosed(true);
            return found;
        }

        Optional<PeriodRow> before = previous(uow, period.getCode());
        if (before.isPresent() && !before.get().isClosed()) {
            found.note("sequence", before.get().getCode() + " is still open, so close that one first");
        }
        return found;
    }

    /** Close a finished month today. */
    public static MonthClose closePeriod(UnitOfWork uow, String code, String closedBy) {
        return closePeriod(uow, code, closedBy, null, false);
    }

    /** Close a finished month on the day given. */
    public static MonthClose closePeriod(UnitOfWork uow, String code, String closedBy, LocalDate on) {
        return closePeriod(uow, code, closedBy, on, false);
    }

    /**
     * Close a month, if it is finished.
     *
     * <p>{@code force} closes over the outstanding items and records who did it. It exists because the
     * last month of a year sometimes has to go in while one settlement is still being argued about,
     * and the alternative to a flag that writes down who overrode the check is somebody updating the
     * row by hand. A clean close is logged as {@code period.closed}; a forced one as an error, {@code
     * period.forced}.
     *
     * @param on the day it closed; null for today
     * @throws ConflictError when the month is already closed, or has something outstanding and {@code
     *     force} is not set
     * @throws PeriodNotFound when no month is filed under {@code code}
     */
    public static MonthClose closePeriod(UnitOfWork uow, String code, String closedBy, LocalDate on, boolean force) {
        MonthClose found = review(uow, code);
        if (found.isClosed()) {
            throw new ConflictError(code + " is already closed");
        }
        if (!found.isClean() && !force) {
            String listed = found.outstanding().stream()
                    .limit(LISTED)
                    .map(Outstanding::toString)
                    .collect(Collectors.joining("; "));
            throw new ConflictError(code + " will not close: " + listed);
        }

        PeriodRow period = uow.ledger().period(code);
        period.setClosed(true);
        period.setClosedOn(on != null ? on : LocalDate.now());
        period.setClosedBy(closedBy);
        uow.flush();
        found.setClosed(true);

        if (found.isClean()) {
            HouseLog.event(
                    LOG,
                    "period.closed",
                    field("period", code),
                    field("by", closedBy),
                    field("settlements", found.settlements()),
                    field("entries", found.entries()),
                    field("margin", found.margin().toString()));
        } else {
            HouseLog.failure(
                    LOG,
                    "period.forced",
                    field("period", code),
                    field("by", closedBy),
                    field("outstanding", found.outstanding().size()));
        }
        return found;
    }

    /**
     * Open a closed month again, and say why in the log.
     *
     * <p>The return has already gone in by the time anybody wants this, so reopening a month means the
     * return will have to be amended. That is a decision for a person; all this does is make it a
     * recorded one, as an error event {@code period.reopened} carrying the reason.
     *
     * @throws MonthEndError when the reason is blank
     * @throws ConflictError when the month is not closed
     * @throws PeriodNotFound when no month is filed under {@code code}
     */
    public static PeriodRow reopenPeriod(UnitOfWork uow, String code, String reason) {
        if (reason.isBlank()) {
            throw new MonthEndError("reopening a month needs a reason");
        }
        PeriodRow period = uow.ledger().period(code);
        if (!period.isClosed()) {
            throw new ConflictError(code + " is not closed");
        }
        period.setClosed(false);
        period.setClosedOn(null);
        period.setClosedBy("");
        uow.flush();
        HouseLog.failure(LOG, "period.reopened", field("period", code), field("reason", reason));
        return period;
    }

    /** The month a late ticket should be keyed into: the earliest still open, if any is. */
    public static Optional<PeriodRow> earliestOpen(UnitOfWork uow) {
        return uow.ledger().openPeriods().stream().findFirst();
    }

    /**
     * What each revenue and expense account did in a month, headings filled in from the accounts
     * under them, in code order.
     *
     * @throws PeriodNotFound when no month is filed under {@code code}
     */
    public static Map<String, BigDecimal> marginsByAccount(UnitOfWork uow, String code) {
        PeriodRow period = uow.ledger().period(code);
        ChartOfAccounts chart = uow.ledger().chart();
        List<JournalEntry> entries = uow.ledger().entriesBetween(period.getStartsOn(), period.getEndsOn());
        TrialBalance balance = Ledger.trialBalance(chart, entries, period.getEndsOn(), period.getStartsOn());
        Map<String, BigDecimal> totals = Ledger.rollup(chart, balance);
        Map<String, BigDecimal> margins = new LinkedHashMap<>();
        for (Account account : chart) {
            if (account.type() == AccountType.REVENUE || account.type() == AccountType.EXPENSE) {
                margins.put(account.code(), totals.get(account.code()));
            }
        }
        return margins;
    }

    private static Optional<PeriodRow> previous(UnitOfWork uow, String code) {
        try {
            return Optional.of(uow.ledger().period(previousCode(code)));
        } catch (PeriodNotFound missing) {
            return Optional.empty();
        }
    }

    private static String previousCode(String code) {
        String[] parts = code.split("-");
        int year = Integer.parseInt(parts[0]);
        int month = Integer.parseInt(parts[1]);
        return month == 1 ? codeOf(year - 1, 12) : codeOf(year, month - 1);
    }

    private static String codeOf(int year, int month) {
        return String.format(Locale.ROOT, "%d-%02d", year, month);
    }
}
