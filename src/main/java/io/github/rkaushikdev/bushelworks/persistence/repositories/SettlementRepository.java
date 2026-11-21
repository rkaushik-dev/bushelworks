package io.github.rkaushikdev.bushelworks.persistence.repositories;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.domain.settlement.Settlement;
import io.github.rkaushikdev.bushelworks.persistence.Columns;
import io.github.rkaushikdev.bushelworks.persistence.Mappers;
import io.github.rkaushikdev.bushelworks.persistence.models.SettlementRow;
import io.github.rkaushikdev.bushelworks.persistence.models.SettlementTicketRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Settlement statements.
 *
 * <p>A settlement is written once and then only ever marked paid. There is no {@code replace} here
 * on purpose: a statement that went out in the post is not a row to be edited, and a wrong one is
 * corrected by voiding it and writing another.
 *
 * <p>{@link #inWindow}, {@link #lineTotals} and {@link #ticketCounts} live here rather than in the
 * services that use them because the tax file and the patronage table were each carrying a private
 * copy. They agreed, which is the dangerous kind of duplication: the two reports are checked against
 * each other at year end, and two copies of the same query cannot disagree even when one of them is
 * wrong. {@link #ticketCounts} also counts every statement in one query rather than one each, which
 * for a full crop year is four thousand queries saved.
 */
public final class SettlementRepository {

    private final EntityManager session;

    public SettlementRepository(EntityManager session) {
        this.session = session;
    }

    /** The session this repository reads and writes through. */
    public EntityManager session() {
        return session;
    }

    /**
     * The stored row for one statement.
     *
     * @throws SettlementNotFound when there is none with that number
     */
    public SettlementRow row(String number) {
        return find(number).orElseThrow(() -> new SettlementNotFound("no settlement " + number));
    }

    /**
     * One statement, read back as the domain object.
     *
     * @throws SettlementNotFound when there is none with that number
     */
    public Settlement get(String number) {
        return Mappers.settlementFromRow(row(number));
    }

    /** Write a statement that names no tickets. */
    public SettlementRow add(Settlement settlement) {
        return add(settlement, List.of());
    }

    /**
     * Write a statement, and the tickets it covers by their keys, and flush them.
     *
     * @throws ConflictError when the number is already taken
     */
    public SettlementRow add(Settlement settlement, Collection<Integer> ticketIds) {
        if (find(settlement.number()).isPresent()) {
            throw new ConflictError("settlement " + settlement.number() + " already exists");
        }
        SettlementRow row = Mappers.settlementToRow(settlement);
        session.persist(row);
        session.flush();
        for (Integer ticketId : ticketIds) {
            SettlementTicketRow covered = new SettlementTicketRow();
            covered.setSettlementId(row.getId());
            covered.setTicketId(ticketId);
            session.persist(covered);
        }
        session.flush();
        return row;
    }

    /** The statements dated {@code day} at every site, in number order. */
    public List<SettlementRow> forDay(LocalDate day) {
        return forDay(day, null);
    }

    /** The statements dated {@code day} at one site, or at every site when it is null. */
    public List<SettlementRow> forDay(LocalDate day, String locationCode) {
        String filter = locationCode == null ? "" : " and s.locationCode = :location";
        TypedQuery<SettlementRow> query = session.createQuery(
                        "select s from SettlementRow s where s.settledOn = :day" + filter + " order by s.number",
                        SettlementRow.class)
                .setParameter("day", day);
        if (locationCode != null) {
            query.setParameter("location", locationCode);
        }
        return query.getResultList();
    }

    /** The live statements dated in a span, both days included, in number order. */
    public List<SettlementRow> inWindow(LocalDate opens, LocalDate closes) {
        return inWindow(opens, closes, null, null);
    }

    /**
     * The live statements dated in a span, both days included, narrowed to a party or a site when
     * either is not null, in number order.
     *
     * <p>Voided statements are never in here. A void is not a negative, it is a statement that never
     * happened, and every caller of this wants it gone.
     *
     * <p>The lines are not read. The callers want a total rather than the rows, and pulling four
     * thousand statements' worth of lines to add up a column is the expensive half of the report; ask
     * {@link #lineTotals} for the sums instead.
     */
    public List<SettlementRow> inWindow(LocalDate opens, LocalDate closes, String partyCode, String locationCode) {
        StringBuilder jpql =
                new StringBuilder("select s from SettlementRow s where s.settledOn >= :opens and s.settledOn <= :closes"
                        + " and s.voided = false");
        if (partyCode != null) {
            jpql.append(" and s.partyCode = :party");
        }
        if (locationCode != null) {
            jpql.append(" and s.locationCode = :location");
        }
        jpql.append(" order by s.number");
        TypedQuery<SettlementRow> query = session.createQuery(jpql.toString(), SettlementRow.class)
                .setParameter("opens", opens)
                .setParameter("closes", closes);
        if (partyCode != null) {
            query.setParameter("party", partyCode);
        }
        if (locationCode != null) {
            query.setParameter("location", locationCode);
        }
        return query.getResultList();
    }

    /** One total per statement and line kind, summed in the database. Nothing asked for is no query. */
    public List<LineTotal> lineTotals(Collection<Integer> settlementIds) {
        if (settlementIds.isEmpty()) {
            return List.of();
        }
        return session
                .createQuery(
                        "select l.settlement.id, l.kind, sum(l.dollars), sum(l.quantity) from SettlementLineRow l"
                                + " where l.settlement.id in :ids group by l.settlement.id, l.kind"
                                + " order by l.settlement.id, l.kind",
                        Object[].class)
                .setParameter("ids", settlementIds)
                .getResultList()
                .stream()
                .map(total -> new LineTotal(
                        (Integer) total[0],
                        (String) total[1],
                        Columns.asDecimal(total[2]),
                        Columns.asDecimal(total[3])))
                .toList();
    }

    /** Dollars by line kind across a set of statements, kinds in the order they first appear. */
    public Map<String, BigDecimal> totalsByKind(Collection<Integer> settlementIds) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (LineTotal line : lineTotals(settlementIds)) {
            totals.merge(line.kind(), line.dollars(), BigDecimal::add);
        }
        return totals;
    }

    /**
     * Loads on each statement asked about, in the order asked, in one query rather than one each.
     *
     * <p>Counted off the tickets a statement names, not its lines: a statement with fourteen tickets
     * on it prints one gross line. A statement that names none counts zero.
     */
    public Map<Integer, Integer> ticketCounts(Collection<Integer> settlementIds) {
        if (settlementIds.isEmpty()) {
            return Map.of();
        }
        Map<Integer, Integer> found = new HashMap<>();
        for (Object[] count : session.createQuery(
                        "select t.settlementId, count(t) from SettlementTicketRow t"
                                + " where t.settlementId in :ids group by t.settlementId",
                        Object[].class)
                .setParameter("ids", settlementIds)
                .getResultList()) {
            found.put((Integer) count[0], Math.toIntExact((Long) count[1]));
        }
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (Integer settlementId : settlementIds) {
            counts.put(settlementId, found.getOrDefault(settlementId, 0));
        }
        return counts;
    }

    /** The live statements not yet paid, oldest first. */
    public List<SettlementRow> unpaid() {
        return session.createQuery(
                        "select s from SettlementRow s where s.paidOn is null and s.voided = false"
                                + " order by s.settledOn, s.number",
                        SettlementRow.class)
                .getResultList();
    }

    /**
     * Record the cheque a statement was paid by.
     *
     * @throws ConflictError when it was paid already
     */
    public SettlementRow markPaid(String number, String chequeNumber, LocalDate paidOn) {
        SettlementRow row = row(number);
        if (row.getPaidOn().isPresent()) {
            throw new ConflictError("settlement " + number + " was already paid on "
                    + row.getPaidOn().get() + " by cheque "
                    + row.getChequeNumber().orElse(""));
        }
        row.setPaidOn(paidOn);
        row.setChequeNumber(chequeNumber);
        session.flush();
        return row;
    }

    private Optional<SettlementRow> find(String number) {
        return Queries.first(
                session.createQuery("select s from SettlementRow s where s.number = :number", SettlementRow.class)
                        .setParameter("number", number));
    }
}
