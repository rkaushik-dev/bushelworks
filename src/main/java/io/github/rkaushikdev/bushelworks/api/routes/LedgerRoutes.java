package io.github.rkaushikdev.bushelworks.api.routes;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.bushelworks.api.Work;
import io.github.rkaushikdev.bushelworks.api.schemas.Body;
import io.github.rkaushikdev.bushelworks.api.security.Requires;
import io.github.rkaushikdev.bushelworks.api.security.Role;
import io.github.rkaushikdev.bushelworks.domain.ledger.Account;
import io.github.rkaushikdev.bushelworks.domain.ledger.ChartOfAccounts;
import io.github.rkaushikdev.bushelworks.domain.ledger.JournalEntry;
import io.github.rkaushikdev.bushelworks.domain.ledger.Ledger;
import io.github.rkaushikdev.bushelworks.domain.ledger.TrialBalance;
import io.github.rkaushikdev.bushelworks.domain.ledger.TrialBalanceRow;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.JournalEntryRow;
import io.github.rkaushikdev.bushelworks.persistence.models.PeriodRow;
import io.github.rkaushikdev.bushelworks.services.monthend.MonthClose;
import io.github.rkaushikdev.bushelworks.services.monthend.MonthEnd;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * General ledger routes, for the office.
 *
 * <p>Read only, apart from closing and reopening a month and filing a year. There is deliberately no
 * route that posts a journal entry: entries come out of the documents that caused them, and an
 * endpoint that writes one directly is an endpoint that can put a number in the books with nothing
 * behind it.
 *
 * <p>The trial balance drops the accounts nothing landed in unless the caller asks for them. A chart
 * of twenty five accounts with four in use reads better as four lines, and the auditor who wants the
 * empty ones asks for them once a year.
 *
 * <p>The journal's lookups sit under segments of their own. A path of {@code /journal/{number}} would
 * swallow {@code /journal/source/settlement/S-1} and answer it as an entry numbered "source", which is
 * what the audit routes were once caught doing.
 */
@RestController
@RequestMapping("/ledger")
@Requires(Role.OFFICE)
@Tag(name = "ledger")
public class LedgerRoutes {

    /** The first day a trial balance with no start counts from: everything ever posted. */
    static final LocalDate BEGINNING = LocalDate.of(1, 1, 1);

    private final Work work;

    public LedgerRoutes(Work work) {
        this.work = work;
    }

    @GetMapping("/accounts")
    @Operation(summary = "Read Chart", description = "The filed chart, in code order.")
    public List<AccountOut> readChart(
            @RequestParam(name = "include_inactive", defaultValue = "false") boolean includeInactive) {
        return work.apply(uow -> {
            ChartOfAccounts chart = uow.ledger().chart(includeInactive);
            List<AccountOut> out = new ArrayList<>();
            for (Account account : chart) {
                out.add(new AccountOut(
                        account.code(),
                        account.name(),
                        account.type().value(),
                        account.parent(),
                        chart.isHeading(account.code())));
            }
            return out;
        });
    }

    @GetMapping("/trial-balance")
    @Operation(
            summary = "Read Trial Balance",
            description = "The books as at a day, or across a span if `since` is given.")
    public TrialBalanceOut readTrialBalance(
            @RequestParam("through") LocalDate through,
            @RequestParam(name = "since", required = false) LocalDate since,
            @RequestParam(name = "include_empty", defaultValue = "false") boolean includeEmpty) {
        return work.apply(uow -> {
            ChartOfAccounts chart = uow.ledger().chart();
            List<JournalEntry> entries = uow.ledger().entriesBetween(since == null ? BEGINNING : since, through);
            TrialBalance balance = Ledger.trialBalance(chart, entries, through, Optional.ofNullable(since));
            List<TrialBalanceRow> rows = includeEmpty ? balance.rows() : balance.nonempty();
            return new TrialBalanceOut(
                    balance.through(),
                    Optional.ofNullable(since),
                    balance.debits(),
                    balance.credits(),
                    balance.balances(),
                    rows.stream()
                            .map(row -> new BalanceRowOut(
                                    row.account().code(),
                                    row.account().name(),
                                    row.account().type().value(),
                                    row.debits(),
                                    row.credits(),
                                    row.balance()))
                            .toList());
        });
    }

    @GetMapping("/journal")
    @Operation(summary = "Read Journal", description = "The journal across a span, oldest first.")
    public List<JournalEntryOut> readJournal(
            @RequestParam("opens") LocalDate opens,
            @RequestParam("closes") LocalDate closes,
            @RequestParam(name = "limit", defaultValue = "200")
                    @Min(value = 1, message = "Input should be greater than or equal to 1")
                    @Max(value = 1000, message = "Input should be less than or equal to 1000")
                    int limit) {
        return work.apply(uow -> uow.ledger().between(opens, closes).stream()
                .limit(limit)
                .map(row -> entryOut(uow, row))
                .toList());
    }

    @GetMapping("/journal/entries/{number}")
    @Operation(summary = "Read Entry")
    public JournalEntryOut readEntry(@PathVariable("number") String number) {
        return work.apply(uow -> entryOut(uow, uow.ledger().row(number)));
    }

    @GetMapping("/journal/source/{source}/{reference}")
    @Operation(summary = "Read Source Trail", description = "Every entry one document raised, the reversals included.")
    public List<JournalEntryOut> readSourceTrail(
            @PathVariable("source") String source, @PathVariable("reference") String reference) {
        return work.apply(uow -> uow.ledger().forSource(source, reference).stream()
                .map(row -> entryOut(uow, row))
                .toList());
    }

    @GetMapping("/periods")
    @Operation(summary = "Read Periods")
    public List<PeriodOut> readPeriods(@RequestParam(name = "open_only", defaultValue = "false") boolean openOnly) {
        return work.apply(uow -> (openOnly ? uow.ledger().openPeriods() : allPeriods(uow))
                .stream().map(LedgerRoutes::periodOut).toList());
    }

    @GetMapping("/periods/{code}")
    @Operation(summary = "Read Period", description = "What closing this month would find, without closing it.")
    public MonthEndOut readPeriod(@PathVariable("code") String code) {
        return work.apply(uow -> monthOut(MonthEnd.review(uow, code)));
    }

    @PostMapping("/periods/{code}/close")
    @Operation(summary = "Close Month")
    public MonthEndOut closeMonth(@PathVariable("code") String code, @RequestBody PeriodCloseIn body) {
        return work.apply(uow -> {
            MonthEndOut out = monthOut(
                    MonthEnd.closePeriod(uow, code, body.closedBy(), body.on().orElse(null), body.force()));
            uow.commit();
            return out;
        });
    }

    @PostMapping("/periods/{code}/reopen")
    @Operation(summary = "Reopen Month")
    public PeriodOut reopenMonth(@PathVariable("code") String code, @RequestBody ReopenIn body) {
        return work.apply(uow -> {
            PeriodOut out = periodOut(MonthEnd.reopenPeriod(uow, code, body.reason()));
            uow.commit();
            return out;
        });
    }

    @PostMapping("/years/{year}")
    @Operation(summary = "File Accounting Year", description = "Put a year of open months on file.")
    public List<PeriodOut> fileAccountingYear(@PathVariable("year") int year) {
        return work.apply(uow -> {
            List<PeriodOut> out = MonthEnd.fileYear(uow, year).stream()
                    .map(LedgerRoutes::periodOut)
                    .toList();
            uow.commit();
            return out;
        });
    }

    private static List<PeriodRow> allPeriods(UnitOfWork uow) {
        return uow.session()
                .createQuery("select p from PeriodRow p order by p.startsOn", PeriodRow.class)
                .getResultList();
    }

    private static PeriodOut periodOut(PeriodRow row) {
        return new PeriodOut(
                row.getCode(),
                row.getStartsOn(),
                row.getEndsOn(),
                row.isClosed(),
                row.getClosedOn(),
                row.getClosedBy());
    }

    private static JournalEntryOut entryOut(UnitOfWork uow, JournalEntryRow row) {
        JournalEntry entry = uow.ledger().entry(row.getNumber());
        return new JournalEntryOut(
                entry.number(),
                entry.postedOn(),
                entry.memo(),
                entry.source(),
                entry.sourceReference(),
                row.getReverses(),
                entry.debits(),
                entry.credits(),
                entry.balances(),
                entry.legs().stream()
                        .map(leg -> new LegOut(leg.accountCode(), leg.side().value(), leg.dollars(), leg.memo()))
                        .toList());
    }

    private static MonthEndOut monthOut(MonthClose found) {
        return new MonthEndOut(
                found.code(),
                found.startsOn(),
                found.endsOn(),
                found.settlements(),
                found.unposted(),
                found.entries(),
                found.revenue(),
                found.expense(),
                found.margin(),
                found.isClean(),
                found.isClosed(),
                found.outstanding().stream()
                        .map(item -> new OutstandingOut(item.kind(), item.detail()))
                        .toList());
    }

    /**
     * A month being closed.
     *
     * @param closedBy who is closing it
     * @param on the day it closed; today when there is none
     * @param force whether to close it with something still outstanding; not unless the caller says so
     */
    public record PeriodCloseIn(String closedBy, Optional<LocalDate> on, boolean force) {

        public PeriodCloseIn {
            Objects.requireNonNull(closedBy, "closedBy");
            Objects.requireNonNull(on, "on");
        }

        /** A close as a caller sends it. */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static PeriodCloseIn from(JsonNode json) {
            return Body.read(json, PeriodCloseIn::read);
        }

        static PeriodCloseIn read(Body in) {
            String closedBy = in.text("closed_by", 0, Integer.MAX_VALUE);
            Optional<LocalDate> on = in.optionalDate("on");
            boolean force = in.flag("force", false);
            return in.build(() -> new PeriodCloseIn(closedBy, on, force));
        }
    }

    /**
     * A closed month being opened again.
     *
     * @param reason why, which the month end refuses to take blank
     */
    public record ReopenIn(String reason) {

        public ReopenIn {
            Objects.requireNonNull(reason, "reason");
        }

        /** A reopening as a caller sends it. */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static ReopenIn from(JsonNode json) {
            return Body.read(json, ReopenIn::read);
        }

        static ReopenIn read(Body in) {
            String reason = in.text("reason", 0, Integer.MAX_VALUE);
            return in.build(() -> new ReopenIn(reason));
        }
    }

    /**
     * One account in the chart.
     *
     * @param code the account code
     * @param name what it is called
     * @param type the kind of account, as its word
     * @param parent the heading it sits under, when it sits under one
     * @param isHeading whether other accounts sit under it, so nothing posts to it
     */
    public record AccountOut(String code, String name, String type, Optional<String> parent, boolean isHeading) {

        public AccountOut {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(parent, "parent");
        }
    }

    /**
     * One account's line on the trial balance.
     *
     * @param code the account code
     * @param name what it is called
     * @param type the kind of account, as its word
     * @param debits everything debited to it
     * @param credits everything credited to it
     * @param balance the two netted on the account's normal side
     */
    public record BalanceRowOut(
            String code, String name, String type, BigDecimal debits, BigDecimal credits, BigDecimal balance) {

        public BalanceRowOut {
            Objects.requireNonNull(code, "code");
        }
    }

    /**
     * The trial balance.
     *
     * @param through the last day counted
     * @param since the first day counted, when it is a span rather than a running total
     * @param debits every debit added up
     * @param credits every credit added up
     * @param balances whether the two agree
     * @param rows the accounts
     */
    public record TrialBalanceOut(
            LocalDate through,
            Optional<LocalDate> since,
            BigDecimal debits,
            BigDecimal credits,
            boolean balances,
            List<BalanceRowOut> rows) {

        public TrialBalanceOut {
            Objects.requireNonNull(through, "through");
            Objects.requireNonNull(since, "since");
            rows = List.copyOf(rows);
        }
    }

    /**
     * One leg of a journal entry.
     *
     * @param accountCode the account it posts to
     * @param side {@code debit} or {@code credit}
     * @param dollars the amount
     * @param memo anything written against it
     */
    public record LegOut(String accountCode, String side, BigDecimal dollars, String memo) {

        public LegOut {
            Objects.requireNonNull(accountCode, "accountCode");
        }
    }

    /**
     * One journal entry.
     *
     * @param number the entry number
     * @param postedOn the day it posted
     * @param memo what it is for
     * @param source what kind of document raised it
     * @param sourceReference which one
     * @param reverses the entry it reverses, when it is a reversal
     * @param debits its debits added up
     * @param credits its credits added up
     * @param balances whether the two agree
     * @param legs its legs
     */
    public record JournalEntryOut(
            String number,
            LocalDate postedOn,
            String memo,
            String source,
            String sourceReference,
            Optional<String> reverses,
            BigDecimal debits,
            BigDecimal credits,
            boolean balances,
            List<LegOut> legs) {

        public JournalEntryOut {
            Objects.requireNonNull(number, "number");
            Objects.requireNonNull(reverses, "reverses");
            legs = List.copyOf(legs);
        }
    }

    /**
     * One accounting month.
     *
     * @param code the month's code, {@code 2024-10}
     * @param startsOn its first day
     * @param endsOn its last day
     * @param closed whether it is closed to posting
     * @param closedOn the day it closed, when it has
     * @param closedBy who closed it
     */
    public record PeriodOut(
            String code,
            LocalDate startsOn,
            LocalDate endsOn,
            boolean closed,
            Optional<LocalDate> closedOn,
            String closedBy) {

        public PeriodOut {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(closedOn, "closedOn");
        }
    }

    /**
     * One thing standing between a month and its close.
     *
     * @param kind which check found it
     * @param detail what was found
     */
    public record OutstandingOut(String kind, String detail) {}

    /**
     * What reviewing or closing a month found.
     *
     * @param code the month's code
     * @param startsOn its first day
     * @param endsOn its last day
     * @param settlements the settlements written in it
     * @param unposted the ones of them not yet in the journal
     * @param entries the journal entries posted in it
     * @param revenue its revenue
     * @param expense its expense
     * @param margin the revenue less the expense
     * @param isClean whether nothing stands in the way of closing it
     * @param closed whether it is closed
     * @param outstanding what stands in the way
     */
    public record MonthEndOut(
            String code,
            LocalDate startsOn,
            LocalDate endsOn,
            int settlements,
            List<String> unposted,
            int entries,
            BigDecimal revenue,
            BigDecimal expense,
            BigDecimal margin,
            boolean isClean,
            boolean closed,
            List<OutstandingOut> outstanding) {

        public MonthEndOut {
            Objects.requireNonNull(code, "code");
            unposted = List.copyOf(unposted);
            outstanding = List.copyOf(outstanding);
        }
    }
}
