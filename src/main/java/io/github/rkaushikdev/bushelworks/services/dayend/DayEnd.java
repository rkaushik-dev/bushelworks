package io.github.rkaushikdev.bushelworks.services.dayend;

import static io.github.rkaushikdev.bushelworks.HouseLog.field;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.HouseLog;
import io.github.rkaushikdev.bushelworks.LookupFailure;
import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.Units;
import io.github.rkaushikdev.bushelworks.ValidationError;
import io.github.rkaushikdev.bushelworks.domain.ledger.JournalEntry;
import io.github.rkaushikdev.bushelworks.domain.ledger.Ledger;
import io.github.rkaushikdev.bushelworks.domain.ledger.TrialBalance;
import io.github.rkaushikdev.bushelworks.domain.position.PositionLine;
import io.github.rkaushikdev.bushelworks.domain.position.PositionReport;
import io.github.rkaushikdev.bushelworks.domain.settlement.Settlement;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.ScaleTicketRow;
import io.github.rkaushikdev.bushelworks.persistence.models.SettlementRow;
import io.github.rkaushikdev.bushelworks.reports.Columns;
import io.github.rkaushikdev.bushelworks.services.positions.Positions;
import io.github.rkaushikdev.bushelworks.services.posting.AlreadyPosted;
import io.github.rkaushikdev.bushelworks.services.posting.PeriodClosed;
import io.github.rkaushikdev.bushelworks.services.posting.Posting;
import io.github.rkaushikdev.bushelworks.services.shipping.Shipping;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.slf4j.Logger;

/**
 * The daily close.
 *
 * <p>At the end of the day somebody has to say what happened, and the software has to be able to
 * prove it. The close does four things and refuses to finish if any of them does not tie:
 *
 * <ol>
 *   <li>counts what came in and what went out
 *   <li>rebuilds the position and compares it with the previous close
 *   <li>posts the day's settlements to the journal and reads the books back
 *   <li>checks that the ticket numbers issued today have no holes in them
 * </ol>
 *
 * <p>A close that fails is not an error to be shrugged at. It means one of the day's documents is
 * wrong and the office finds out now rather than at year end.
 *
 * <p>Step three posts each statement rather than re-adding its lines, because re-adding them would be
 * a second copy of the arithmetic the statement already did, agreeing with itself and proving
 * nothing. Posting is the stronger check for the same work: an entry whose legs do not balance cannot
 * be written down at all, and a day whose trial balance is out names an account rather than a
 * settlement number, which is what the person looking for it actually needs.
 */
public final class DayEnd {

    /** The house named on the day book page when nobody names another. */
    public static final String HOUSE_NAME = "BUSHELWORKS ELEVATOR";

    private static final Logger LOG = HouseLog.getLogger("dayend");

    private static final int WIDTH = 72;

    /** The pounds per bushel the day's intake is counted at, whatever came across the scale. */
    private static final BigDecimal POUNDS_PER_BUSHEL = new BigDecimal("56");

    /** The commodities whose shipments the close counts out. */
    private static final List<String> SHIPPED_COMMODITIES = List.of("CORN", "SOYB", "HRW");

    private static final int LISTED_HOLES = 10;

    private static final int LISTED_PROBLEMS = 5;

    private DayEnd() {}

    /** A total kept on a statement's header, beside the same total worked out from its lines. */
    private record Copy(String name, BigDecimal stored, BigDecimal computed) {

        boolean drifted() {
            return Money.amount(stored).compareTo(Money.amount(computed)) != 0;
        }
    }

    /** Close one day across the whole house, posting for real, at the standard tolerance. */
    public static Close runClose(UnitOfWork uow, LocalDate day) {
        return runClose(uow, day, CloseOptions.defaults());
    }

    /**
     * Close one day at one site, or across the house.
     *
     * <p>A dry run builds every entry and reports what would refuse, without writing any of them. The
     * office runs it that way at four o'clock to see whether the day is going to close before it starts
     * chasing a driver. A clean close is logged as {@code day.closed}; one with problems as {@code
     * day.will_not_close}.
     *
     * <p>The tickets and statements counted are the site's, but the bushels in are every live ticket
     * of the day across the house, at 56 pounds a bushel whatever the
     * commodity, and the bushels out are everything corn, soybeans and wheat shipped through the day.
     */
    public static Close runClose(UnitOfWork uow, LocalDate day, CloseOptions options) {
        String locationCode = options.locationCode();
        String site = locationCode.equals(CloseOptions.EVERY_SITE) ? null : locationCode;
        Close close = new Close(day, locationCode);

        List<ScaleTicketRow> tickets = ticketsOn(uow, day, site);
        List<ScaleTicketRow> live =
                tickets.stream().filter(row -> !row.isVoided()).toList();
        close.setTicketsWritten(live.size());
        close.setTicketsVoided(tickets.size() - live.size());

        BigDecimal pounds = io.github.rkaushikdev.bushelworks.persistence.Columns.asDecimal(uow.session()
                .createQuery(
                        "select sum(t.grossPounds - t.tarePounds) from ScaleTicketRow t"
                                + " where t.scaleDate = :day and t.voided = false",
                        BigDecimal.class)
                .setParameter("day", day)
                .getSingleResult());
        close.setBushelsIn(Units.quantize(Decimals.divide(pounds, POUNDS_PER_BUSHEL), Units.BUSHEL_PLACES));

        List<SettlementRow> settlements = uow.settlements().forDay(day, site);
        close.setSettlementsWritten(settlements.size());
        close.setSettledValue(
                Money.total(settlements.stream().map(SettlementRow::getNet).toList()));

        BigDecimal out = close.bushelsOut();
        for (String code : SHIPPED_COMMODITIES) {
            out = Units.quantize(out.add(Shipping.shippedBushels(uow, code, day, site)), Units.BUSHEL_PLACES);
        }
        close.setBushelsOut(out);

        checkNumbering(close, live);
        List<Settlement> sound = checkSettlements(close, uow, settlements);
        postSettlements(close, uow, sound, options.dryRun());
        checkTheBooks(close, uow);
        checkPosition(close, uow, day, locationCode, options.positionTolerance());

        if (close.isClean()) {
            HouseLog.event(
                    LOG,
                    "day.closed",
                    field("day", day),
                    field("location", locationCode),
                    field("tickets", close.ticketsWritten()),
                    field("settlements", close.settlementsWritten()),
                    field("bushels_in", close.bushelsIn()));
        } else {
            HouseLog.failure(
                    LOG,
                    "day.will_not_close",
                    field("day", day),
                    field("location", locationCode),
                    field("problems", close.discrepancies().size()));
        }
        return close;
    }

    private static List<ScaleTicketRow> ticketsOn(UnitOfWork uow, LocalDate day, String site) {
        TypedQuery<ScaleTicketRow> query = uow.session()
                .createQuery(
                        "select t from ScaleTicketRow t where t.scaleDate = :day"
                                + (site == null ? "" : " and t.locationCode = :location")
                                + " order by t.ticketNumber",
                        ScaleTicketRow.class)
                .setParameter("day", day);
        if (site != null) {
            query.setParameter("location", site);
        }
        return query.getResultList();
    }

    /** Ticket stock is pre numbered. A hole means a ticket is in a drawer. */
    private static void checkNumbering(Close close, List<ScaleTicketRow> tickets) {
        TreeSet<Long> numeric = new TreeSet<>();
        int numbered = 0;
        for (ScaleTicketRow row : tickets) {
            String number = row.getTicketNumber();
            String tail = number.substring(number.lastIndexOf('-') + 1);
            if (!tail.isEmpty() && tail.chars().allMatch(Character::isDigit)) {
                numeric.add(Long.parseLong(tail));
                numbered++;
            }
        }
        if (numbered < 2) {
            return;
        }
        long lowest = numeric.first();
        long highest = numeric.last();
        long missing = highest - lowest + 1 - numeric.size();
        if (missing <= 0) {
            return;
        }
        List<String> listed = new ArrayList<>();
        for (long number = lowest; number <= highest && listed.size() < LISTED_HOLES; number++) {
            if (!numeric.contains(number)) {
                listed.add(Long.toString(number));
            }
        }
        close.note("ticket numbering", missing + " numbers unaccounted for: " + String.join(", ", listed));
    }

    /**
     * The stored totals have to agree with the lines they were summed from.
     *
     * <p>The gross, the deductions and the net are kept on the header so the register can be listed
     * without reading every line. That makes them a copy, and a copy is something that can drift.
     * Rebuilding the statement from its lines and comparing is one comparison instead of three
     * re-implementations of the arithmetic, and it checks all three columns.
     *
     * <p>Only the statements that reconcile are handed on to be posted. A header the house cannot trust
     * is not something to put in the ledger.
     */
    private static List<Settlement> checkSettlements(Close close, UnitOfWork uow, List<SettlementRow> settlements) {
        List<Settlement> sound = new ArrayList<>();
        for (SettlementRow row : settlements) {
            Settlement statement = uow.settlements().get(row.getNumber());
            List<Copy> copies = List.of(
                    new Copy("gross", row.getGross(), statement.gross()),
                    new Copy("deductions", row.getDeductions(), statement.deductions()),
                    new Copy("net", row.getNet(), statement.net()));
            Optional<Copy> drifted = copies.stream().filter(Copy::drifted).findFirst();
            if (drifted.isPresent()) {
                Copy copy = drifted.get();
                close.note(
                        "settlement",
                        row.getNumber() + " " + copy.name() + " " + copy.stored() + " does not match its lines "
                                + copy.computed());
                continue;
            }
            sound.add(statement);
        }
        return sound;
    }

    /**
     * Write the day's statements into the journal, or say why one will not go.
     *
     * <p>A statement that has already been posted is not a problem. The close is run twice most days,
     * once before the last load is keyed and once after, and the second run has nothing to do for the
     * ones the first run wrote. A closed month, or no month on file at all, stops the posting where it
     * is, and the close then reports no entries posted.
     */
    private static void postSettlements(Close close, UnitOfWork uow, List<Settlement> statements, boolean dryRun) {
        List<String> posted = new ArrayList<>();
        for (Settlement statement : statements) {
            String number = uow.ledger().nextNumber("J", close.day());
            try {
                if (dryRun) {
                    Posting.entryForSettlement(statement, number, close.day());
                } else {
                    JournalEntry entry = Posting.postSettlement(uow, statement, number, close.day());
                    posted.add(entry.number());
                }
            } catch (AlreadyPosted already) {
                continue;
            } catch (PeriodClosed refusal) {
                close.note("period", refusal.getMessage());
                return;
            } catch (LookupFailure missing) {
                close.note("period", "nothing to post into: " + missing.getMessage());
                return;
            } catch (ValidationError | ConflictError refusal) {
                close.note("settlement", statement.number() + " will not post: " + refusal.getMessage());
            }
        }
        close.setEntriesPosted(posted);
    }

    /**
     * Read the day back off the journal and make sure it adds up.
     *
     * <p>Every entry balances on its own or it could not have been written, so this can only fail on a
     * hand written back fill. That is exactly the case worth catching, and it is the reason the check
     * reads the table rather than the entries the close just built.
     */
    private static void checkTheBooks(Close close, UnitOfWork uow) {
        List<JournalEntry> entries = uow.ledger().entriesBetween(close.day(), close.day());
        if (entries.isEmpty()) {
            return;
        }
        TrialBalance balance = Ledger.trialBalance(uow.ledger().chart(), entries, close.day());
        if (!balance.balances()) {
            close.note(
                    "journal",
                    "the day posts " + balance.debits() + " of debits against " + balance.credits() + " of credits");
        }
    }

    private static void checkPosition(
            Close close, UnitOfWork uow, LocalDate day, String locationCode, BigDecimal tolerance) {
        PositionReport today = Positions.buildReport(uow, day, List.of(), locationCode);
        PositionReport yesterday = Positions.buildReport(uow, day.minusDays(1), List.of(), locationCode);
        close.setPosition(today);

        List<String> before = yesterday.commodities();
        for (String code : today.commodities()) {
            BigDecimal now = today.byCommodity(code).netLong();
            BigDecimal then =
                    before.contains(code) ? yesterday.byCommodity(code).netLong() : BigDecimal.ZERO;
            BigDecimal move = now.subtract(then).abs();
            if (move.compareTo(tolerance) > 0) {
                close.note(
                        "position",
                        code + " moved " + move + " bushels overnight, above the " + tolerance + " tolerance");
            }
        }
    }

    /**
     * Refuse to let the day be signed off with something outstanding.
     *
     * @throws ConflictError naming the first five problems
     */
    public static void requireClean(Close close) {
        if (close.isClean()) {
            return;
        }
        String listed = close.discrepancies().stream()
                .limit(LISTED_PROBLEMS)
                .map(Discrepancy::toString)
                .collect(Collectors.joining("; "));
        throw new ConflictError(close.day() + " will not close: " + listed);
    }

    /** The close as it goes in the day book, for the house. */
    public static String render(Close close) {
        return render(close, HOUSE_NAME);
    }

    /** The close as it goes in the day book: the counts, the position, and every problem found. */
    public static String render(Close close, String houseName) {
        List<String> lines = new ArrayList<>();
        lines.add(Columns.centred(houseName, WIDTH));
        lines.add(Columns.centred("DAILY CLOSE " + close.day() + "  " + close.locationCode(), WIDTH));
        lines.add("=".repeat(WIDTH));
        lines.add(count("tickets written", close.ticketsWritten()));
        lines.add(count("tickets voided", close.ticketsVoided()));
        lines.add(count("bushels in", close.bushelsIn()));
        lines.add(count("bushels out", close.bushelsOut()));
        lines.add(count("net bushels", close.netBushels()));
        lines.add(count("settlements written", close.settlementsWritten()));
        lines.add(count("settled value", close.settledValue()));
        lines.add(count("entries posted", close.entriesPosted().size()));
        lines.add("-".repeat(WIDTH));
        close.position().ifPresent(position -> {
            for (String code : position.commodities()) {
                PositionLine line = position.byCommodity(code);
                lines.add(count(code, line.netLong()) + " net long");
            }
        });
        lines.add("-".repeat(WIDTH));
        if (close.isClean()) {
            lines.add("closed clean");
        } else {
            lines.add(close.discrepancies().size() + " problems:");
            close.discrepancies().forEach(item -> lines.add("  " + item));
        }
        return String.join("\n", lines);
    }

    private static String count(String label, Object value) {
        return Columns.left(label, 28) + Columns.right(String.valueOf(value), 12);
    }
}
