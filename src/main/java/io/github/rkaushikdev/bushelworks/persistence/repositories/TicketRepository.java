package io.github.rkaushikdev.bushelworks.persistence.repositories;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.domain.tickets.ScaleTicket;
import io.github.rkaushikdev.bushelworks.persistence.Columns;
import io.github.rkaushikdev.bushelworks.persistence.Mappers;
import io.github.rkaushikdev.bushelworks.persistence.models.ScaleTicketRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Scale tickets. Append only, keyed by location and ticket number.
 *
 * <p>A ticket is only unique inside one elevator, which is why nearly every method here takes a
 * location as well as a number. The one that does not is {@link #byNumber}, and it hands back a list
 * for exactly that reason.
 */
public final class TicketRepository {

    private final EntityManager session;

    public TicketRepository(EntityManager session) {
        this.session = session;
    }

    /** The session this repository reads and writes through. */
    public EntityManager session() {
        return session;
    }

    /**
     * The stored row for one ticket.
     *
     * @throws TicketNotFound when the site has no ticket with that number
     */
    public ScaleTicketRow row(String locationCode, String ticketNumber) {
        return Queries.first(session.createQuery(
                                "select t from ScaleTicketRow t"
                                        + " where t.locationCode = :location and t.ticketNumber = :number",
                                ScaleTicketRow.class)
                        .setParameter("location", locationCode)
                        .setParameter("number", ticketNumber))
                .orElseThrow(() -> new TicketNotFound("no ticket " + ticketNumber + " at " + locationCode));
    }

    /**
     * One ticket, read back as the domain object.
     *
     * @throws TicketNotFound when the site has no ticket with that number
     */
    public ScaleTicket get(String locationCode, String ticketNumber) {
        return Mappers.ticketFromRow(row(locationCode, ticketNumber));
    }

    public boolean exists(String locationCode, String ticketNumber) {
        Long count = session.createQuery(
                        "select count(t) from ScaleTicketRow t"
                                + " where t.locationCode = :location and t.ticketNumber = :number",
                        Long.class)
                .setParameter("location", locationCode)
                .setParameter("number", ticketNumber)
                .getSingleResult();
        return count > 0;
    }

    /**
     * Write a new ticket, filed under {@code partyCode}, and flush it.
     *
     * @throws ConflictError when the site already has a ticket with that number
     */
    public ScaleTicketRow add(ScaleTicket ticket, String partyCode) {
        if (exists(ticket.locationCode(), ticket.ticketNumber())) {
            throw new ConflictError("ticket " + ticket.ticketNumber() + " already written at " + ticket.locationCode());
        }
        ScaleTicketRow row = Mappers.ticketToRow(ticket, partyCode);
        session.persist(row);
        session.flush();
        return row;
    }

    /**
     * Mark a ticket void, keeping its weights, with the reason in its remarks.
     *
     * @throws ConflictError when the ticket is already void or has been settled
     */
    public ScaleTicketRow voidTicket(String locationCode, String ticketNumber, String reason) {
        ScaleTicketRow row = row(locationCode, ticketNumber);
        if (row.isVoided()) {
            throw new ConflictError("ticket " + ticketNumber + " is already voided");
        }
        if (row.isSettled()) {
            throw new ConflictError("ticket " + ticketNumber + " has already been settled");
        }
        row.setVoided(true);
        row.setRemarks("VOID: " + reason);
        session.flush();
        return row;
    }

    /**
     * Every ticket carrying a number, across the sites, oldest first.
     *
     * <p>A number is only unique inside one elevator, so this is a list. It exists because a
     * complaint arrives with a number read out over the telephone and nobody says which house it was
     * written at.
     */
    public List<ScaleTicketRow> byNumber(String ticketNumber) {
        return session.createQuery(
                        "select t from ScaleTicketRow t where t.ticketNumber = :number order by t.scaleDate, t.id",
                        ScaleTicketRow.class)
                .setParameter("number", ticketNumber)
                .getResultList();
    }

    /** Every ticket filed under a party, oldest first. */
    public List<ScaleTicketRow> forParty(String partyCode) {
        return forParty(partyCode, null);
    }

    /** The tickets filed under a party on or after {@code since}, or every one when it is null. */
    public List<ScaleTicketRow> forParty(String partyCode, LocalDate since) {
        String filter = since == null ? "" : " and t.scaleDate >= :since";
        TypedQuery<ScaleTicketRow> query = session.createQuery(
                        "select t from ScaleTicketRow t where t.partyCode = :party" + filter
                                + " order by t.scaleDate, t.id",
                        ScaleTicketRow.class)
                .setParameter("party", partyCode);
        if (since != null) {
            query.setParameter("since", since);
        }
        return query.getResultList();
    }

    /** Every ticket neither settled nor void, at every site, oldest first. */
    public List<ScaleTicketRow> awaitingSettlement() {
        return awaitingSettlement(null);
    }

    /** The tickets neither settled nor void at one site, or at every site when it is null. */
    public List<ScaleTicketRow> awaitingSettlement(String locationCode) {
        String filter = locationCode == null ? "" : " and t.locationCode = :location";
        TypedQuery<ScaleTicketRow> query = session.createQuery(
                "select t from ScaleTicketRow t where t.settled = false and t.voided = false" + filter
                        + " order by t.scaleDate, t.id",
                ScaleTicketRow.class);
        if (locationCode != null) {
            query.setParameter("location", locationCode);
        }
        return query.getResultList();
    }

    /** Net pounds of one commodity over a span, both days included, for the caller to convert. Voids excluded. */
    public BigDecimal bushelsReceived(String commodityCode, LocalDate start, LocalDate end) {
        BigDecimal total = session.createQuery(
                        "select sum(t.grossPounds - t.tarePounds) from ScaleTicketRow t"
                                + " where t.commodityCode = :commodity and t.scaleDate >= :start"
                                + " and t.scaleDate <= :end and t.voided = false",
                        BigDecimal.class)
                .setParameter("commodity", commodityCode)
                .setParameter("start", start)
                .setParameter("end", end)
                .getSingleResult();
        return Columns.asDecimal(total);
    }
}
