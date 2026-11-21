package io.github.rkaushikdev.bushelworks.services.patronage;

import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.Ordered;
import io.github.rkaushikdev.bushelworks.Units;
import io.github.rkaushikdev.bushelworks.domain.settlement.LineKind;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.SettlementRow;
import io.github.rkaushikdev.bushelworks.persistence.repositories.LineTotal;
import io.github.rkaushikdev.bushelworks.reports.patronage.PatronageError;
import io.github.rkaushikdev.bushelworks.reports.patronage.PatronageLine;
import io.github.rkaushikdev.bushelworks.reports.patronage.PatronageYear;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Building the patronage table out of settled statements.
 *
 * <p>Everything the board is shown comes off settlements, not off tickets. A ticket is a weighing; a
 * settlement is the house and the producer agreeing what the weighing was worth, and only the second
 * one can be added up.
 *
 * <p>Two things this deliberately does not know:
 *
 * <ul>
 *   <li>who is a member. The membership roll is kept in the accounting package with the equity and
 *       the stock, and copying it into this database would give the house two rolls that disagree by
 *       August. It comes in as an argument.
 *   <li>what a bushel of anything was worth. Gross dollars are on the settlement.
 * </ul>
 *
 * <p>Voided settlements are dropped. A void is not a negative; it is a statement that never
 * happened, and netting it against a real one hides both.
 */
public final class Patronage {

    /**
     * The line kinds that are the house's margin rather than the producer's money. An assessment is
     * collected for the checkoff board and passed straight on, and an advance is the producer's own
     * money coming back, so neither is in here.
     */
    public static final List<LineKind> MARGIN_KINDS =
            List.of(LineKind.DISCOUNT, LineKind.DRYING, LineKind.STORAGE, LineKind.SERVICE);

    /** What a year that names no site is stamped with. */
    public static final String EVERY_SITE = "*";

    /** How many producers the top list names when nobody says otherwise. */
    public static final int DEFAULT_TOP = 10;

    /** The smallest share worth writing a cheque for when nobody says otherwise. */
    public static final BigDecimal DEFAULT_MINIMUM = new BigDecimal("5.00");

    private static final Set<String> MARGIN_VALUES =
            MARGIN_KINDS.stream().map(LineKind::value).collect(Collectors.toUnmodifiableSet());

    private Patronage() {}

    /** Every producer who settled at any site in a span, everybody a member. */
    public static PatronageYear buildYear(UnitOfWork uow, LocalDate opens, LocalDate closes) {
        return buildYear(uow, opens, closes, null, null);
    }

    /**
     * Every producer who settled in a span, both days included, with what they were worth.
     *
     * <p>Bushels come off the gross lines, the margin off the {@link #MARGIN_KINDS} lines, the gross
     * off the statement headers and the loads off the tickets each statement covers. The whole year
     * is four queries however many statements it holds.
     *
     * @param locationCode one site; null or empty for every site, and the year is then stamped
     *     {@link #EVERY_SITE}
     * @param members the codes on the membership roll; null treats everybody as a member, which is
     *     what a house that is not a co-op wants and what a co-op gets wrong exactly once
     * @throws PatronageError when the span closes before it opens
     */
    public static PatronageYear buildYear(
            UnitOfWork uow, LocalDate opens, LocalDate closes, String locationCode, Collection<String> members) {
        if (closes.isBefore(opens)) {
            throw new PatronageError("the year closes before it opens");
        }
        String site = locationCode == null || locationCode.isEmpty() ? null : locationCode;
        Set<String> roll = members == null ? null : Set.copyOf(members);

        List<SettlementRow> settled = uow.settlements().inWindow(opens, closes, null, site);
        List<Integer> ids = settled.stream().map(SettlementRow::getId).toList();
        Map<Integer, Integer> counts = uow.settlements().ticketCounts(ids);
        Map<Integer, BigDecimal> volume = new HashMap<>();
        Map<Integer, BigDecimal> margin = new HashMap<>();
        for (LineTotal line : uow.settlements().lineTotals(ids)) {
            if (line.kind().equals(LineKind.GROSS.value())) {
                volume.merge(line.settlementId(), line.quantity(), BigDecimal::add);
            } else if (MARGIN_VALUES.contains(line.kind())) {
                margin.merge(line.settlementId(), line.dollars(), BigDecimal::add);
            }
        }

        Map<String, Tally> byParty = new TreeMap<>();
        for (SettlementRow row : settled) {
            Tally tally = byParty.computeIfAbsent(row.getPartyCode(), code -> new Tally());
            tally.bushels = tally.bushels.add(volume.getOrDefault(row.getId(), BigDecimal.ZERO));
            tally.gross = tally.gross.add(row.getGross());
            tally.charges = tally.charges.add(margin.getOrDefault(row.getId(), BigDecimal.ZERO));
            tally.loads += counts.getOrDefault(row.getId(), 0);
        }

        Map<String, String> names = names(uow, byParty.keySet());
        List<PatronageLine> lines = byParty.entrySet().stream()
                .map(entry -> {
                    String code = entry.getKey();
                    Tally tally = entry.getValue();
                    return new PatronageLine(
                            code,
                            names.getOrDefault(code, code),
                            Units.quantize(tally.bushels, Units.BUSHEL_PLACES),
                            Money.amount(tally.gross),
                            Money.amount(tally.charges),
                            roll == null || roll.contains(code),
                            tally.loads);
                })
                .toList();
        return new PatronageYear(site == null ? EVERY_SITE : site, opens, closes, lines);
    }

    /**
     * The first of September to the last of August, which is how grain runs. The calendar year is the
     * wrong span for this, and using it splits one harvest across two tables.
     */
    public static CropYear cropYear(int year) {
        return new CropYear(LocalDate.of(year, 9, 1), LocalDate.of(year + 1, 8, 31));
    }

    /** The crop year opening in {@code year}, at every site, everybody a member. */
    public static PatronageYear buildCropYear(UnitOfWork uow, int year) {
        return buildCropYear(uow, year, null, null);
    }

    /**
     * The crop year opening in {@code year}.
     *
     * @param locationCode one site; null or empty for every site
     * @param members the membership roll; null treats everybody as a member
     */
    public static PatronageYear buildCropYear(
            UnitOfWork uow, int year, String locationCode, Collection<String> members) {
        CropYear span = cropYear(year);
        return buildYear(uow, span.opens(), span.closes(), locationCode, members);
    }

    /** The ten biggest producers by bushels, biggest first. */
    public static List<String> topProducers(PatronageYear year) {
        return topProducers(year, DEFAULT_TOP);
    }

    /** The {@code count} biggest producers by bushels, biggest first; see {@link PatronageYear#top}. */
    public static List<String> topProducers(PatronageYear year, int count) {
        return year.top(count).stream().map(PatronageLine::partyCode).toList();
    }

    /** Split a pool and drop the shares under five dollars. */
    public static Map<String, BigDecimal> allocate(PatronageYear year, BigDecimal pool) {
        return allocate(year, pool, DEFAULT_MINIMUM);
    }

    /**
     * Split a pool across the members and drop the shares not worth the postage.
     *
     * <p>A cheque for forty cents costs more to write, post and reconcile than it is worth, so a share
     * under the minimum stays in the pool. What is dropped is not handed to anybody else: it goes back
     * to unallocated reserves, which is the board's decision to make and not this one's.
     */
    public static Map<String, BigDecimal> allocate(PatronageYear year, BigDecimal pool, BigDecimal minimum) {
        BigDecimal floor = Money.amount(minimum);
        Map<String, BigDecimal> kept = new LinkedHashMap<>();
        year.allocations(pool).forEach((code, share) -> {
            if (share.compareTo(floor) >= 0) {
                kept.put(code, share);
            }
        });
        return Ordered.copyOf(kept);
    }

    /** Names off the party table, by code. A code with no party row is left out. */
    private static Map<String, String> names(UnitOfWork uow, Collection<String> codes) {
        TreeSet<String> wanted = new TreeSet<>(codes);
        if (wanted.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new HashMap<>();
        for (Object[] party : uow.session()
                .createQuery("select p.code, p.name from Party p where p.code in :codes", Object[].class)
                .setParameter("codes", wanted)
                .getResultList()) {
            names.put((String) party[0], (String) party[1]);
        }
        return names;
    }

    /** What one producer adds up to over the span. */
    private static final class Tally {

        private BigDecimal bushels = BigDecimal.ZERO;
        private BigDecimal gross = BigDecimal.ZERO;
        private BigDecimal charges = BigDecimal.ZERO;
        private int loads;
    }
}
