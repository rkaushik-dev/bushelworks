package io.github.rkaushikdev.bushelworks.persistence.repositories;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.persistence.Columns;
import io.github.rkaushikdev.bushelworks.persistence.models.ComplaintActionRow;
import io.github.rkaushikdev.bushelworks.persistence.models.ComplaintRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The complaint register.
 *
 * <p>Rows in and rows out rather than domain objects. The domain register is an in memory list that
 * answers questions about a whole season, and building it per call would read every complaint the
 * house has ever taken to answer one about a bin. The service layer builds the domain object when it
 * wants the season and works off rows when it wants one.
 */
public final class ComplaintRepository {

    private final EntityManager session;

    public ComplaintRepository(EntityManager session) {
        this.session = session;
    }

    /** The session this repository reads and writes through. */
    public EntityManager session() {
        return session;
    }

    /**
     * The complaint filed under {@code reference}.
     *
     * @throws ComplaintNotFound when there is none
     */
    public ComplaintRow row(String reference) {
        return find(reference).orElseThrow(() -> new ComplaintNotFound("no complaint " + reference));
    }

    /**
     * File a complaint and flush it.
     *
     * @throws ConflictError when its reference is already on the register
     */
    public ComplaintRow add(ComplaintRow row) {
        if (find(row.getReference()).isPresent()) {
            throw new ConflictError(row.getReference() + " is already on the register");
        }
        session.persist(row);
        session.flush();
        return row;
    }

    /** Every open complaint, oldest first. */
    public List<ComplaintRow> openComplaints() {
        return openComplaints(null);
    }

    /** The open complaints against one bin, oldest first; every open complaint when it is null or empty. */
    public List<ComplaintRow> openComplaints(String binId) {
        boolean narrowed = binId != null && !binId.isEmpty();
        TypedQuery<ComplaintRow> query = session.createQuery(
                "select c from ComplaintRow c where c.outcome = 'open'"
                        + (narrowed ? " and c.binId = :bin" : "")
                        + " order by c.raisedOn, c.reference",
                ComplaintRow.class);
        if (narrowed) {
            query.setParameter("bin", binId);
        }
        return query.getResultList();
    }

    /** The complaints raised in a span, both days included, oldest first. */
    public List<ComplaintRow> between(LocalDate opens, LocalDate closes) {
        return session.createQuery(
                        "select c from ComplaintRow c where c.raisedOn >= :opens and c.raisedOn <= :closes"
                                + " order by c.raisedOn, c.reference",
                        ComplaintRow.class)
                .setParameter("opens", opens)
                .setParameter("closes", closes)
                .getResultList();
    }

    /** The complaints filed against one party, oldest first. */
    public List<ComplaintRow> forParty(String partyCode) {
        return session.createQuery(
                        "select c from ComplaintRow c where c.partyCode = :party order by c.raisedOn, c.id",
                        ComplaintRow.class)
                .setParameter("party", partyCode)
                .getResultList();
    }

    /** How many complaints each bin has attracted in a span, by bin. Complaints naming no bin are left out. */
    public Map<String, Integer> binCounts(LocalDate opens, LocalDate closes) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Object[] count : session.createQuery(
                        "select c.binId, count(c) from ComplaintRow c where c.binId is not null"
                                + " and c.raisedOn >= :opens and c.raisedOn <= :closes"
                                + " group by c.binId order by c.binId",
                        Object[].class)
                .setParameter("opens", opens)
                .setParameter("closes", closes)
                .getResultList()) {
            counts.put((String) count[0], Math.toIntExact((Long) count[1]));
        }
        return counts;
    }

    /**
     * Add a line to what was done about a complaint, numbered after the last one, and flush it.
     *
     * @throws ComplaintNotFound when there is no complaint under {@code reference}
     */
    public ComplaintActionRow act(String reference, LocalDate takenOn, String takenBy, String what) {
        ComplaintRow row = row(reference);
        ComplaintActionRow action = new ComplaintActionRow();
        action.setSequence(row.getActions().size() + 1);
        action.setTakenOn(takenOn);
        action.setTakenBy(takenBy);
        action.setWhat(what);
        row.addAction(action);
        session.persist(action);
        session.flush();
        return action;
    }

    /** What the house has paid out to settle the complaints it closed in a span, both days included. */
    public BigDecimal creditsBetween(LocalDate opens, LocalDate closes) {
        BigDecimal total = session.createQuery(
                        "select sum(c.credit) from ComplaintRow c where c.closedOn >= :opens and c.closedOn <= :closes",
                        BigDecimal.class)
                .setParameter("opens", opens)
                .setParameter("closes", closes)
                .getSingleResult();
        return Columns.asDecimal(total);
    }

    private Optional<ComplaintRow> find(String reference) {
        return Queries.first(
                session.createQuery("select c from ComplaintRow c where c.reference = :reference", ComplaintRow.class)
                        .setParameter("reference", reference));
    }
}
