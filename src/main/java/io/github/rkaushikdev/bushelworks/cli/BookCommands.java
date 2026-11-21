package io.github.rkaushikdev.bushelworks.cli;

import static io.github.rkaushikdev.bushelworks.reports.Columns.left;
import static io.github.rkaushikdev.bushelworks.reports.Columns.right;

import io.github.rkaushikdev.bushelworks.Text;
import io.github.rkaushikdev.bushelworks.domain.ledger.Account;
import io.github.rkaushikdev.bushelworks.domain.ledger.ChartOfAccounts;
import io.github.rkaushikdev.bushelworks.domain.ledger.JournalEntry;
import io.github.rkaushikdev.bushelworks.domain.ledger.Ledger;
import io.github.rkaushikdev.bushelworks.domain.ledger.Leg;
import io.github.rkaushikdev.bushelworks.domain.ledger.Side;
import io.github.rkaushikdev.bushelworks.domain.ledger.TrialBalance;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.PeriodRow;
import io.github.rkaushikdev.bushelworks.reports.ledger.LedgerPages;
import io.github.rkaushikdev.bushelworks.services.closing.Closing;
import io.github.rkaushikdev.bushelworks.services.closing.YearClose;
import io.github.rkaushikdev.bushelworks.services.monthend.MonthClose;
import io.github.rkaushikdev.bushelworks.services.monthend.MonthEnd;
import io.github.rkaushikdev.bushelworks.services.monthend.Outstanding;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 * The ledger commands.
 *
 * <p>The month end ones are the point. {@code review} says whether a month would close and {@code
 * close-month} closes it, and they are separate commands rather than one with a dry run flag because
 * the office runs the first one on the third of the month and the second one a week later, and a
 * flag that changes whether a command writes is a flag somebody eventually forgets.
 *
 * <p>{@code file-year} is run once each December. It is not part of {@code init-db} because a house
 * being set up mid year wants the current year filed, not 2000.
 */
final class BookCommands {

    /** The commands this module adds to the group. */
    static final List<Class<?>> COMMANDS = List.of(
            PrintChart.class,
            PrintTrialBalance.class,
            Income.class,
            BalanceSheet.class,
            Journal.class,
            FileYear.class,
            Review.class,
            CloseMonth.class,
            ReopenMonth.class,
            OpenMonth.class,
            YearEnd.class,
            CloseYear.class);

    /** The first day there is: a balance with no start day is a running total from here. */
    static final LocalDate BEGINNING = LocalDate.of(1, 1, 1);

    private BookCommands() {}

    /** The chart and its trial balance through a day, from {@code since} when it is given. */
    record Books(ChartOfAccounts chart, TrialBalance balance) {

        static Books of(UnitOfWork uow, LocalDate through, LocalDate since) {
            ChartOfAccounts chart = uow.ledger().chart();
            List<JournalEntry> entries = uow.ledger().entriesBetween(since != null ? since : BEGINNING, through);
            return new Books(chart, Ledger.trialBalance(chart, entries, through, Optional.ofNullable(since)));
        }
    }

    /** The chart of accounts. */
    @Command(name = "chart", description = "The filed chart of accounts.")
    static final class PrintChart implements Callable<Integer> {

        @ParentCommand
        private Main main;

        private boolean inactive;

        @Option(names = "--inactive", description = "Include closed accounts.")
        void inactive(boolean given) {
            inactive |= given;
        }

        @Option(names = "--active-only", description = "Only the accounts still open. The default.")
        void activeOnly(boolean given) {
            inactive &= !given;
        }

        @Override
        public Integer call() {
            PrintWriter out = main.out();
            try (UnitOfWork uow = main.unitOfWork()) {
                ChartOfAccounts chart = uow.ledger().chart(inactive);
                for (Account account : chart) {
                    int depth = chart.lineage(account.code()).size() - 1;
                    String mark = chart.isHeading(account.code()) ? "  " : ". ";
                    out.println(left(account.code(), 8) + "  ".repeat(depth) + mark + account.name());
                }
            }
            return Main.OK;
        }
    }

    /** The trial balance. */
    @Command(name = "trial-balance", description = "The books as at a day.")
    static final class PrintTrialBalance implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Parameters(index = "0", paramLabel = "THROUGH", converter = Arguments.Day.class)
        private LocalDate through;

        @Option(
                names = "--since",
                paramLabel = "[" + Arguments.DAY_FORMAT + "]",
                converter = Arguments.Day.class,
                description = "Make it a period rather than a running total.")
        private LocalDate since;

        private boolean empty;

        @Option(names = "--empty", description = "Show untouched accounts.")
        void empty(boolean given) {
            empty |= given;
        }

        @Option(names = "--used-only", description = "Only the accounts something was posted to. The default.")
        void usedOnly(boolean given) {
            empty &= !given;
        }

        @Option(
                names = "--csv",
                paramLabel = "FILE",
                converter = Arguments.OutputFile.class,
                description = "Write it as a file for the accounting package instead.")
        private Path csv;

        @Override
        public Integer call() {
            TrialBalance balance;
            try (UnitOfWork uow = main.unitOfWork()) {
                balance = Books.of(uow, through, since).balance();
            }
            if (csv != null) {
                TextFiles.write(csv, LedgerPages.toCsv(balance, empty));
                int accounts = Text.lines(TextFiles.read(csv)).size() - 1;
                main.out().println(accounts + " accounts to " + csv);
                return Main.OK;
            }
            main.out().println(LedgerPages.trialBalancePage(balance, empty));
            return Main.OK;
        }
    }

    /** The income statement. */
    @Command(name = "income", description = "What the house earned between two days.")
    static final class Income implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Parameters(index = "0", paramLabel = "OPENS", converter = Arguments.Day.class)
        private LocalDate opens;

        @Parameters(index = "1", paramLabel = "CLOSES", converter = Arguments.Day.class)
        private LocalDate closes;

        @Override
        public Integer call() {
            Books books;
            try (UnitOfWork uow = main.unitOfWork()) {
                books = Books.of(uow, closes, opens);
            }
            main.out().println(LedgerPages.incomeStatement(books.chart(), books.balance(), opens));
            return Main.OK;
        }
    }

    /** The balance sheet. */
    @Command(name = "balance-sheet", description = "What the house owns and owes as at a day.")
    static final class BalanceSheet implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Parameters(index = "0", paramLabel = "THROUGH", converter = Arguments.Day.class)
        private LocalDate through;

        @Override
        public Integer call() {
            Books books;
            try (UnitOfWork uow = main.unitOfWork()) {
                books = Books.of(uow, through, null);
            }
            main.out().println(LedgerPages.balanceSheet(books.chart(), books.balance()));
            return Main.OK;
        }
    }

    /** The journal between two days. */
    @Command(name = "journal", description = "Every entry posted between two days.")
    static final class Journal implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Parameters(index = "0", paramLabel = "OPENS", converter = Arguments.Day.class)
        private LocalDate opens;

        @Parameters(index = "1", paramLabel = "CLOSES", converter = Arguments.Day.class)
        private LocalDate closes;

        @Override
        public Integer call() {
            List<JournalEntry> entries;
            try (UnitOfWork uow = main.unitOfWork()) {
                entries = uow.ledger().entriesBetween(opens, closes);
            }
            PrintWriter out = main.out();
            for (JournalEntry entry : entries) {
                out.println(entry.number() + "  " + entry.postedOn() + "  " + entry.memo());
                for (Leg leg : entry.legs()) {
                    String side = leg.side() == Side.DEBIT ? "dr" : "  cr";
                    out.println("    " + left(leg.accountCode(), 8) + side + " "
                            + right(leg.dollars().toString(), 14));
                }
            }
            return Main.OK;
        }
    }

    /** Twelve months on file. */
    @Command(name = "file-year", description = "Put twelve open months on file.")
    static final class FileYear implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Parameters(index = "0", paramLabel = "YEAR")
        private int year;

        @Override
        public Integer call() {
            int count;
            try (UnitOfWork uow = main.unitOfWork()) {
                count = MonthEnd.fileYear(uow, year).size();
                uow.commit();
            }
            main.out().println(count + " months filed for " + year);
            return Main.OK;
        }
    }

    /** Whether a month would close. */
    @Command(name = "review", description = "Say whether a month would close, and write nothing.")
    static final class Review implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Parameters(index = "0", paramLabel = "PERIOD")
        private String period;

        @Override
        public Integer call() {
            MonthClose found;
            try (UnitOfWork uow = main.unitOfWork()) {
                found = MonthEnd.review(uow, period);
            }
            PrintWriter out = main.out();
            out.println(found.code() + "  " + found.startsOn() + " to " + found.endsOn());
            out.println("  settlements " + found.settlements() + ", entries " + found.entries());
            out.println("  revenue " + found.revenue() + ", expense " + found.expense());
            out.println("  margin " + found.margin());
            if (found.isClean()) {
                out.println("  would close clean");
                return Main.OK;
            }
            out.println("  " + found.outstanding().size() + " outstanding:");
            for (Outstanding item : found.outstanding()) {
                out.println("    " + item);
            }
            return Main.OK;
        }
    }

    /** A month, closed. */
    @Command(name = "close-month", description = "Close a month.")
    static final class CloseMonth implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Parameters(index = "0", paramLabel = "PERIOD")
        private String period;

        @Option(names = "--by", required = true, paramLabel = "TEXT", description = "Who is closing it.")
        private String closedBy;

        @Option(
                names = "--on",
                paramLabel = "[" + Arguments.DAY_FORMAT + "]",
                converter = Arguments.Day.class,
                description = "The day it was closed, if not today.")
        private LocalDate on;

        private boolean force;

        @Option(names = "--force", description = "Close over what is outstanding, and record who did.")
        void force(boolean given) {
            force |= given;
        }

        @Option(names = "--no-force", description = "Refuse a month with something outstanding. The default.")
        void noForce(boolean given) {
            force &= !given;
        }

        @Override
        public Integer call() {
            MonthClose found;
            try (UnitOfWork uow = main.unitOfWork()) {
                found = MonthEnd.closePeriod(uow, period, closedBy, on, force);
                uow.commit();
            }
            PrintWriter out = main.out();
            if (found.isClean()) {
                out.println(period + " closed");
                return Main.OK;
            }
            out.println(period + " closed over " + found.outstanding().size() + " outstanding items:");
            for (Outstanding item : found.outstanding()) {
                out.println("  " + item);
            }
            return Main.OK;
        }
    }

    /** A closed month, open again. */
    @Command(name = "reopen-month", description = "Open a closed month again. The return will have to be amended.")
    static final class ReopenMonth implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Parameters(index = "0", paramLabel = "PERIOD")
        private String period;

        @Option(names = "--reason", required = true, paramLabel = "TEXT", description = "Why, for the log.")
        private String reason;

        @Override
        public Integer call() {
            try (UnitOfWork uow = main.unitOfWork()) {
                MonthEnd.reopenPeriod(uow, period, reason);
                uow.commit();
            }
            main.out().println(period + " reopened");
            return Main.OK;
        }
    }

    /** The month a late ticket lands in. */
    @Command(name = "open-month", description = "Which month a ticket keyed late belongs in.")
    static final class OpenMonth implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Override
        public Integer call() {
            Optional<PeriodRow> earliest;
            try (UnitOfWork uow = main.unitOfWork()) {
                earliest = MonthEnd.earliestOpen(uow);
            }
            main.out()
                    .println(earliest.map(row -> row.getCode() + "  " + row.getStartsOn() + " to " + row.getEndsOn())
                            .orElse("no months on file"));
            return Main.OK;
        }
    }

    /** What closing a year would post. */
    @Command(name = "year-end", description = "Say what closing a year would post, and write nothing.")
    static final class YearEnd implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Parameters(index = "0", paramLabel = "YEAR")
        private int year;

        @Override
        public Integer call() {
            YearClose found;
            try (UnitOfWork uow = main.unitOfWork()) {
                found = Closing.reviewYear(uow, year);
            }
            main.out().println(Closing.render(found));
            return Main.OK;
        }
    }

    /** A year, closed into equity. */
    @Command(name = "close-year", description = "Empty the revenue and expense accounts into equity.")
    static final class CloseYear implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Parameters(index = "0", paramLabel = "YEAR")
        private int year;

        @Option(names = "--by", required = true, paramLabel = "TEXT", description = "Who is closing it.")
        private String closedBy;

        @Option(
                names = "--retained",
                paramLabel = "TEXT",
                description = "Close into this account instead of retained earnings.")
        private String retained;

        private boolean force;

        @Option(names = "--force", description = "Close over what is outstanding, and record who did.")
        void force(boolean given) {
            force |= given;
        }

        @Option(names = "--no-force", description = "Refuse a year with something outstanding. The default.")
        void noForce(boolean given) {
            force &= !given;
        }

        @Override
        public Integer call() {
            String page;
            try (UnitOfWork uow = main.unitOfWork()) {
                page = Closing.render(Closing.closeYear(uow, year, closedBy, retained, force));
                uow.commit();
            }
            main.out().println(page);
            return Main.OK;
        }
    }
}
