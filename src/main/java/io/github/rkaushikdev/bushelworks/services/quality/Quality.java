package io.github.rkaushikdev.bushelworks.services.quality;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.domain.complaints.Complaint;
import io.github.rkaushikdev.bushelworks.domain.complaints.ComplaintError;
import io.github.rkaushikdev.bushelworks.domain.complaints.Outcome;
import io.github.rkaushikdev.bushelworks.domain.complaints.Register;
import io.github.rkaushikdev.bushelworks.domain.complaints.Source;
import io.github.rkaushikdev.bushelworks.persistence.UnitOfWork;
import io.github.rkaushikdev.bushelworks.persistence.models.ComplaintRow;
import io.github.rkaushikdev.bushelworks.persistence.models.ScaleTicketRow;
import io.github.rkaushikdev.bushelworks.persistence.repositories.ComplaintNotFound;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

/**
 * Taking a complaint, working it, and closing it.
 *
 * <p>The domain {@link Register} is an in memory list that answers questions about a whole season.
 * The table is the season. So this maps one to the other in both directions, and does the two things
 * the table cannot do on its own: refuse a complaint that contradicts the record, and say whether the
 * house can defend it.
 *
 * <p>Closing is where the rules are. A rejected complaint carries no credit, a complaint cannot close
 * before it was raised, and a complaint that is already closed stays closed. All three of those are
 * in the domain object, so closing goes through it rather than writing the columns directly.
 */
public final class Quality {

    /** How long a retained sample stays on the shelf before it is thrown out, in days. */
    public static final int RETENTION_DAYS = 45;

    /** A bin complained about this many times is a pattern rather than a one off. */
    public static final int REPEAT_THRESHOLD = 3;

    private Quality() {}

    /** The complaint a row holds. */
    public static Complaint toDomain(ComplaintRow row) {
        return new Complaint(
                row.getReference(),
                Source.of(row.getSource()),
                row.getRaisedOn(),
                row.getDeliveredOn(),
                row.getSubject(),
                row.getPartyCode(),
                row.getBinId(),
                row.getTicketNumber(),
                row.getSaleNumber(),
                row.getProbeId(),
                Outcome.of(row.getOutcome()),
                row.getCredit(),
                row.getClosedOn(),
                row.getNote());
    }

    /** A new row holding a complaint, its credit rounded to cents. */
    public static ComplaintRow toRow(Complaint complaint) {
        ComplaintRow row = new ComplaintRow();
        row.setReference(complaint.reference());
        row.setSource(complaint.source().value());
        row.setSubject(complaint.subject());
        row.setRaisedOn(complaint.raisedOn());
        row.setDeliveredOn(complaint.deliveredOn());
        row.setPartyCode(complaint.partyCode().orElse(null));
        row.setBinId(complaint.binId().orElse(null));
        row.setTicketNumber(complaint.ticketNumber().orElse(null));
        row.setSaleNumber(complaint.saleNumber().orElse(null));
        row.setProbeId(complaint.probeId().orElse(null));
        row.setOutcome(complaint.outcome().value());
        row.setCredit(Money.amount(complaint.credit()));
        row.setClosedOn(complaint.closedOn().orElse(null));
        row.setNote(complaint.note());
        return row;
    }

    /** Take a complaint that names nothing beyond who rang, when and about what. */
    public static ComplaintRow fileComplaint(
            UnitOfWork uow,
            String reference,
            Source source,
            String subject,
            LocalDate raisedOn,
            LocalDate deliveredOn) {
        return fileComplaint(uow, reference, source, subject, raisedOn, deliveredOn, FilingOptions.defaults());
    }

    /**
     * Take a complaint from a caller named by its value, {@code buyer}, {@code producer} or {@code
     * haulier}.
     *
     * @throws IllegalArgumentException when the source is not one of those
     */
    public static ComplaintRow fileComplaint(
            UnitOfWork uow,
            String reference,
            String source,
            String subject,
            LocalDate raisedOn,
            LocalDate deliveredOn,
            FilingOptions options) {
        return fileComplaint(uow, reference, Source.of(source), subject, raisedOn, deliveredOn, options);
    }

    /**
     * Take a complaint.
     *
     * <p>A named ticket is checked against the ticket file. Getting that wrong is common because the
     * number is read out over a bad telephone line, and a complaint filed against a ticket that does
     * not exist is one nobody can work.
     *
     * @throws ComplaintError when the complaint does not hold together, or its ticket is not a live one
     *     weighed on or before the delivery
     * @throws ConflictError when the reference is already on the register
     */
    public static ComplaintRow fileComplaint(
            UnitOfWork uow,
            String reference,
            Source source,
            String subject,
            LocalDate raisedOn,
            LocalDate deliveredOn,
            FilingOptions options) {
        Complaint complaint = Complaint.builder()
                .reference(reference)
                .source(source)
                .raisedOn(raisedOn)
                .deliveredOn(deliveredOn)
                .subject(subject)
                .partyCode(options.partyCode().orElse(null))
                .binId(options.binId().orElse(null))
                .ticketNumber(options.ticketNumber().orElse(null))
                .saleNumber(options.saleNumber().orElse(null))
                .probeId(options.probeId().orElse(null))
                .build();
        if (options.ticketNumber().isPresent()) {
            checkTicket(uow, options.ticketNumber().get(), deliveredOn);
        }
        return uow.complaints().add(toRow(complaint));
    }

    private static void checkTicket(UnitOfWork uow, String ticketNumber, LocalDate deliveredOn) {
        List<ScaleTicketRow> rows = uow.tickets().byNumber(ticketNumber).stream()
                .filter(row -> !row.isVoided())
                .toList();
        if (rows.isEmpty()) {
            throw new ComplaintError("no live ticket " + ticketNumber);
        }
        if (rows.stream().allMatch(row -> row.getScaleDate().isAfter(deliveredOn))) {
            throw new ComplaintError("ticket " + ticketNumber + " was weighed after " + deliveredOn);
        }
    }

    /**
     * Record what was done, so the file reads as a sequence and not a note.
     *
     * @throws ComplaintNotFound when there is no complaint under {@code reference}
     */
    public static void act(UnitOfWork uow, String reference, LocalDate takenOn, String takenBy, String what) {
        uow.complaints().act(reference, takenOn, takenBy, what);
    }

    /** Close a complaint with no credit and no note, the outcome named by its value. */
    public static ComplaintRow close(UnitOfWork uow, String reference, String outcome, LocalDate on) {
        return close(uow, reference, Outcome.of(outcome), on);
    }

    /** Close a complaint with no credit and no note. */
    public static ComplaintRow close(UnitOfWork uow, String reference, Outcome outcome, LocalDate on) {
        return close(uow, reference, outcome, on, null, null);
    }

    /** Close a complaint with a credit and no note. */
    public static ComplaintRow close(
            UnitOfWork uow, String reference, Outcome outcome, LocalDate on, BigDecimal credit) {
        return close(uow, reference, outcome, on, credit, null);
    }

    /**
     * Close a complaint through the domain object so the rules apply.
     *
     * @param credit what the house gave back; null for nothing
     * @param note what the office writes on it; null or empty keeps the note it has
     * @throws ConflictError when it is closed already
     * @throws ComplaintError when it would close before it was raised, without an outcome, or rejected
     *     with a credit
     * @throws ComplaintNotFound when there is no complaint under {@code reference}
     */
    public static ComplaintRow close(
            UnitOfWork uow, String reference, Outcome outcome, LocalDate on, BigDecimal credit, String note) {
        ComplaintRow row = uow.complaints().row(reference);
        Complaint closed =
                toDomain(row).close(outcome, on, credit != null ? credit : Money.ZERO_AMOUNT, note != null ? note : "");
        row.setOutcome(closed.outcome().value());
        row.setClosedOn(closed.closedOn().orElse(null));
        row.setCredit(closed.credit());
        row.setNote(closed.note());
        uow.flush();
        return row;
    }

    /** Build the whole season in memory, for the questions that need it: every complaint raised in a span. */
    public static Register registerBetween(UnitOfWork uow, LocalDate opens, LocalDate closes) {
        Register register = new Register();
        for (ComplaintRow row : uow.complaints().between(opens, closes)) {
            register.add(toDomain(row));
        }
        return register;
    }

    /** Every open complaint, oldest first. */
    public static List<Complaint> openComplaints(UnitOfWork uow) {
        return openComplaints(uow, null);
    }

    /** The open complaints against one bin, oldest first; every open complaint when it is null or empty. */
    public static List<Complaint> openComplaints(UnitOfWork uow, String binId) {
        return uow.complaints().openComplaints(binId).stream()
                .map(Quality::toDomain)
                .toList();
    }

    /**
     * Open complaints raised in a span with no sample left to argue from, oldest first.
     *
     * <p>These are the ones to settle rather than fight, and knowing which they are in the first week
     * saves a fortnight of correspondence that ends the same way.
     */
    public static List<Complaint> indefensible(UnitOfWork uow, LocalDate opens, LocalDate closes) {
        return registerBetween(uow, opens, closes).indefensible(RETENTION_DAYS);
    }

    /** Bins complained about three times or more in a span, in bin order. */
    public static List<String> repeatBins(UnitOfWork uow, LocalDate opens, LocalDate closes) {
        return repeatBins(uow, opens, closes, REPEAT_THRESHOLD);
    }

    /**
     * Bins that have attracted more complaints in a span than the house is happy with, in bin order.
     *
     * <p>Counted in the database rather than by building the register, because this is the one
     * question that gets asked over a five year span.
     */
    public static List<String> repeatBins(UnitOfWork uow, LocalDate opens, LocalDate closes, int threshold) {
        Map<String, Integer> counts = uow.complaints().binCounts(opens, closes);
        return counts.entrySet().stream()
                .filter(count -> count.getValue() >= threshold)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    /** The page the manager looks at once a month, for the complaints raised in a span. */
    public static ComplaintSummary summary(UnitOfWork uow, LocalDate opens, LocalDate closes) {
        Register register = registerBetween(uow, opens, closes);
        List<Complaint> complaints = register.complaints();
        return new ComplaintSummary(
                opens,
                closes,
                complaints.size(),
                register.openComplaints().size(),
                (int) complaints.stream().filter(Complaint::isLate).count(),
                register.upheldRate(),
                register.credits(),
                Register.bySource(complaints),
                references(register.indefensible(RETENTION_DAYS)),
                register.repeatBins(),
                Complaint.NORMAL_WINDOW_DAYS);
    }

    /** What the house paid out to settle the complaints it closed in a span, both days included. */
    public static BigDecimal creditsBetween(UnitOfWork uow, LocalDate opens, LocalDate closes) {
        return uow.complaints().creditsBetween(opens, closes);
    }

    /** The references of some complaints, in the order given. */
    public static List<String> references(Iterable<Complaint> complaints) {
        return StreamSupport.stream(complaints.spliterator(), false)
                .map(Complaint::reference)
                .toList();
    }
}
