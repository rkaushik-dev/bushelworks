package io.github.rkaushikdev.bushelworks.services.posting;

import static io.github.rkaushikdev.bushelworks.HouseLog.field;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.HouseLog;
import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.domain.ledger.JournalEntry;
import io.github.rkaushikdev.bushelworks.domain.ledger.Leg;
import io.github.rkaushikdev.bushelworks.domain.ledger.Side;
import io.github.rkaushikdev.bushelworks.domain.settlement.LineKind;
import io.github.rkaushikdev.bushelworks.domain.settlement.Settlement;
import io.github.rkaushikdev.bushelworks.domain.settlement.SettlementLine;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.JournalEntryRow;
import io.github.rkaushikdev.bushelworks.persistence.models.PeriodRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;

/**
 * Turning a settlement into a journal entry.
 *
 * <p>The statement and the journal entry are the same event written down for two different readers,
 * and this is the only place that knows both shapes. Everything above it works in bushels and line
 * kinds; everything below it works in accounts and sides.
 *
 * <p>The mapping is data, a {@link PostingMap}, because houses disagree about where a quality
 * discount goes, and a house that books discounts to margin only has to file a different map.
 *
 * <p>The one rule that is not configurable: the entry balances because the net was worked out from
 * the same lines it is posted from. If it ever does not, the settlement and the statement disagree,
 * and posting a plug to make it balance would hide exactly the thing worth finding.
 */
public final class Posting {

    /** The source every settlement entry carries, so the journal can be searched by statement. */
    public static final String SOURCE = "settlement";

    private static final Logger LOG = HouseLog.getLogger("posting");

    private Posting() {}

    /**
     * What a leg says: the kind of line, and for a checkoff posted to its own commission's account,
     * the levies it carries.
     */
    private static String legDescription(
            LineKind kind, String account, PostingMap mapping, List<SettlementLine> lines) {
        if (account.equals(mapping.accountFor(kind))) {
            return kind.value();
        }
        return kind.value() + " "
                + lines.stream().map(SettlementLine::code).distinct().sorted().collect(Collectors.joining(", "));
    }

    /** One journal entry for one statement, posted on the settlement day through the standard map. */
    public static JournalEntry entryForSettlement(Settlement settlement, String number) {
        return entryForSettlement(settlement, number, null);
    }

    /** One journal entry for one statement through the standard map. */
    public static JournalEntry entryForSettlement(Settlement settlement, String number, LocalDate postedOn) {
        return entryForSettlement(settlement, number, postedOn, PostingMap.DEFAULT, "");
    }

    /**
     * One journal entry for one statement.
     *
     * <p>Lines are grouped by kind rather than posted one to a leg. A statement with four discount
     * factors on it is one credit to the discount account, because the ledger is not the place to look
     * up which factor it was: the statement is, and the entry points at it. The net is credited to the
     * payable when the house owes it and debited when the producer does.
     *
     * @param postedOn the day it posts; null for the settlement day
     * @param memo what the entry says; empty for {@code settlement <number> <party>}
     * @throws PostingError when the statement has nothing to post, or the entry would not balance
     */
    public static JournalEntry entryForSettlement(
            Settlement settlement, String number, LocalDate postedOn, PostingMap mapping, String memo) {
        settlement.checkBalances();
        List<Leg> legs = new ArrayList<>();
        for (LineKind kind : LineKind.values()) {
            Map<String, List<SettlementLine>> byAccount = settlement.ofKind(kind).stream()
                    .collect(Collectors.groupingBy(mapping::accountFor, TreeMap::new, Collectors.toList()));
            byAccount.forEach((account, lines) -> {
                BigDecimal subtotal =
                        Money.total(lines.stream().map(SettlementLine::dollars).toList());
                if (subtotal.signum() != 0) {
                    legs.add(new Leg(
                            account, mapping.sideFor(kind), subtotal, legDescription(kind, account, mapping, lines)));
                }
            });
        }

        BigDecimal net = settlement.net();
        if (net.signum() > 0) {
            legs.add(new Leg(mapping.payable(), Side.CREDIT, net, "due the producer"));
        } else if (net.signum() < 0) {
            legs.add(new Leg(mapping.payable(), Side.DEBIT, net.negate(), "owed by the producer"));
        }

        if (legs.size() < 2) {
            throw new PostingError(settlement.number() + ": nothing to post, the statement is empty");
        }

        JournalEntry entry = new JournalEntry(
                number,
                postedOn != null ? postedOn : settlement.settledOn(),
                memo.isEmpty() ? "settlement " + settlement.number() + " " + settlement.partyId() : memo,
                legs,
                SOURCE,
                settlement.number());
        if (!entry.balances()) {
            throw new PostingError(settlement.number() + ": the statement nets to " + settlement.net()
                    + " but the entry is out by " + entry.debits().subtract(entry.credits()));
        }
        return entry;
    }

    /** Build and check the entry for a statement on its settlement day, without writing anything. */
    public static PostingPlan planSettlement(UnitOfWork uow, Settlement settlement, String number) {
        return planSettlement(uow, settlement, number, null, PostingMap.DEFAULT);
    }

    /**
     * Build the entry and check it against the filed chart, without writing anything.
     *
     * <p>The day close runs this over everything it is about to post, so a refusal comes back as a list
     * rather than as the fourth of nine settlements failing halfway through. A statement already in
     * the journal is a warning on the plan, not a refusal.
     *
     * @param postedOn the day it would post; null for the settlement day
     */
    public static PostingPlan planSettlement(
            UnitOfWork uow, Settlement settlement, String number, LocalDate postedOn, PostingMap mapping) {
        JournalEntry entry = entryForSettlement(settlement, number, postedOn, mapping, "");
        entry.checkAgainst(uow.ledger().chart());
        List<String> warnings = new ArrayList<>();
        if (uow.ledger().isPosted(SOURCE, settlement.number())) {
            warnings.add(settlement.number() + " is already in the journal");
        }
        return new PostingPlan(entry, settlement.number(), settlement.net(), warnings);
    }

    /**
     * Refuse a posting into a month whose return has already gone in.
     *
     * @throws PeriodClosed when the period covering {@code day} is closed
     * @throws io.github.rkaushikdev.bushelworks.persistence.repositories.PeriodNotFound when no period
     *     covers it
     */
    public static void requireOpenPeriod(UnitOfWork uow, LocalDate day) {
        PeriodRow period = uow.ledger().periodCovering(day);
        if (period.isClosed()) {
            throw new PeriodClosed(period.getCode() + " was closed on "
                    + period.getClosedOn().map(LocalDate::toString).orElse("None") + ", so " + day
                    + " cannot take a posting; put it in the open month instead");
        }
    }

    /** Write the entry for a statement on its settlement day, through the standard map. */
    public static JournalEntry postSettlement(UnitOfWork uow, Settlement settlement, String number) {
        return postSettlement(uow, settlement, number, null);
    }

    /** Write the entry for a statement through the standard map. */
    public static JournalEntry postSettlement(
            UnitOfWork uow, Settlement settlement, String number, LocalDate postedOn) {
        return postSettlement(uow, settlement, number, postedOn, PostingMap.DEFAULT);
    }

    /**
     * Write the entry for a statement. Once, and into an open month. Logged as {@code
     * settlement.posted}.
     *
     * @param postedOn the day it posts; null for the settlement day
     * @throws PeriodClosed when that day's month is closed
     * @throws AlreadyPosted when the statement is in the journal already
     */
    public static JournalEntry postSettlement(
            UnitOfWork uow, Settlement settlement, String number, LocalDate postedOn, PostingMap mapping) {
        LocalDate day = postedOn != null ? postedOn : settlement.settledOn();
        requireOpenPeriod(uow, day);
        if (uow.ledger().isPosted(SOURCE, settlement.number())) {
            throw new AlreadyPosted(
                    settlement.number() + " is already in the journal; reverse it rather than posting it twice");
        }
        JournalEntry entry = entryForSettlement(settlement, number, day, mapping, "");
        entry.checkAgainst(uow.ledger().chart());
        uow.ledger().post(entry);
        HouseLog.event(
                LOG,
                "settlement.posted",
                field("settlement", settlement.number()),
                field("entry", entry.number()),
                field("party", settlement.partyId()),
                field("net", settlement.net()),
                field("posted_on", day));
        return entry;
    }

    /** Post the opposite of what a settlement posted, memo {@code reversal of <entry>}. */
    public static JournalEntry reverseSettlement(
            UnitOfWork uow, String settlementNumber, String number, LocalDate postedOn) {
        return reverseSettlement(uow, settlementNumber, number, postedOn, "");
    }

    /**
     * Post the opposite of what a settlement posted.
     *
     * <p>Takes the settlement number rather than the entry number because that is what the office has
     * in front of it. A settlement posted, reversed and posted again has three entries, and this
     * reverses the last one still standing. Logged as {@code settlement.reversed}.
     *
     * @param reason the reversal's memo; empty for {@code reversal of <entry>}
     * @throws PeriodClosed when the month of {@code postedOn} is closed
     * @throws PostingError when the settlement was never posted
     * @throws ConflictError when every entry for it has been reversed already
     */
    public static JournalEntry reverseSettlement(
            UnitOfWork uow, String settlementNumber, String number, LocalDate postedOn, String reason) {
        requireOpenPeriod(uow, postedOn);
        List<JournalEntryRow> posted = uow.ledger().forSource(SOURCE, settlementNumber);
        if (posted.isEmpty()) {
            throw new PostingError(settlementNumber + " was never posted, so it cannot be reversed");
        }
        Set<String> alreadyUndone = posted.stream()
                .map(JournalEntryRow::getReverses)
                .flatMap(Optional::stream)
                .collect(Collectors.toSet());
        List<JournalEntryRow> standing = posted.stream()
                .filter(row -> row.getReverses().isEmpty() && !alreadyUndone.contains(row.getNumber()))
                .toList();
        if (standing.isEmpty()) {
            throw new ConflictError("every entry for " + settlementNumber + " has already been reversed");
        }
        JournalEntry original =
                uow.ledger().entry(standing.get(standing.size() - 1).getNumber());
        JournalEntry back =
                original.reverse(number, postedOn, reason.isEmpty() ? "reversal of " + original.number() : reason);
        back.checkAgainst(uow.ledger().chart());
        uow.ledger().post(back, original.number());
        HouseLog.event(
                LOG,
                "settlement.reversed",
                field("settlement", settlementNumber),
                field("entry", back.number()),
                field("reverses", original.number()),
                field("posted_on", postedOn));
        return back;
    }

    /** Which of these statements, by number, have never reached the journal, in number order. */
    public static List<String> unposted(UnitOfWork uow, Map<String, Settlement> settlements) {
        return settlements.keySet().stream()
                .sorted()
                .filter(number -> !uow.ledger().isPosted(SOURCE, number))
                .toList();
    }
}
