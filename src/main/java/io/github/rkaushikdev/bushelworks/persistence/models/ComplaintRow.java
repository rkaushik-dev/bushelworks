package io.github.rkaushikdev.bushelworks.persistence.models;

import io.github.rkaushikdev.bushelworks.persistence.Columns;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.hibernate.annotations.Check;

/**
 * One thing somebody rang up about.
 *
 * <p>A complaint is filed against something, and which something varies: a car the buyer refused
 * names a sale, a producer arguing about his moisture names a ticket, and a feedlot ringing about a
 * smell names neither and only knows which week it was. So every one of those columns may be empty
 * and the only thing that may not is the subject.
 *
 * <p>The retained sample is the load bearing one. {@code probe_id} says which sample was kept, and
 * whether it was still on the shelf when the complaint arrived decides whether the house argues or
 * pays. It is a plain string rather than a foreign key because samples are thrown out on a rota and
 * the row that named one should not stop that happening.
 */
@Entity
@Table(
        name = "complaint",
        uniqueConstraints = @UniqueConstraint(name = "uq_complaint_reference", columnNames = "reference"),
        indexes = {
            @Index(name = "ix_complaint_bin_id", columnList = "bin_id, raised_on"),
            @Index(name = "ix_complaint_party_code", columnList = "party_code"),
            @Index(name = "ix_complaint_outcome", columnList = "outcome, raised_on")
        })
@Check(name = "ck_complaint_credit_not_negative", constraints = "credit >= 0")
@Check(name = "ck_complaint_raised_after_delivery", constraints = "raised_on >= delivered_on")
public class ComplaintRow extends Timestamped {

    @Column(name = "reference", length = 24, nullable = false)
    private String reference;

    @Column(name = "source", length = 12, nullable = false)
    private String source;

    @Column(name = "subject", length = 200, nullable = false)
    private String subject;

    @Column(name = "raised_on", nullable = false)
    private LocalDate raisedOn;

    @Column(name = "delivered_on", nullable = false)
    private LocalDate deliveredOn;

    @Column(name = "party_code", length = 24)
    private String partyCode;

    @Column(name = "bin_id", length = 12)
    private String binId;

    @Column(name = "ticket_number", length = 24)
    private String ticketNumber;

    @Column(name = "sale_number", length = 24)
    private String saleNumber;

    @Column(name = "probe_id", length = 24)
    private String probeId;

    @Column(name = "outcome", length = 12, nullable = false)
    private String outcome = "open";

    @Column(name = "credit", precision = Columns.MONEY_PRECISION, scale = Columns.MONEY_SCALE, nullable = false)
    private BigDecimal credit = BigDecimal.ZERO;

    @Column(name = "closed_on")
    private LocalDate closedOn;

    @Column(name = "note", length = 400, nullable = false)
    private String note = "";

    @OneToMany(mappedBy = "complaint", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("takenOn, sequence")
    private List<ComplaintActionRow> actions = new ArrayList<>();

    public ComplaintRow() {}

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public LocalDate getRaisedOn() {
        return raisedOn;
    }

    public void setRaisedOn(LocalDate raisedOn) {
        this.raisedOn = raisedOn;
    }

    public LocalDate getDeliveredOn() {
        return deliveredOn;
    }

    public void setDeliveredOn(LocalDate deliveredOn) {
        this.deliveredOn = deliveredOn;
    }

    public Optional<String> getPartyCode() {
        return Optional.ofNullable(partyCode);
    }

    /** Null leaves {@code party_code} empty. */
    public void setPartyCode(String partyCode) {
        this.partyCode = partyCode;
    }

    public Optional<String> getBinId() {
        return Optional.ofNullable(binId);
    }

    /** Null leaves {@code bin_id} empty. */
    public void setBinId(String binId) {
        this.binId = binId;
    }

    public Optional<String> getTicketNumber() {
        return Optional.ofNullable(ticketNumber);
    }

    /** Null leaves {@code ticket_number} empty. */
    public void setTicketNumber(String ticketNumber) {
        this.ticketNumber = ticketNumber;
    }

    public Optional<String> getSaleNumber() {
        return Optional.ofNullable(saleNumber);
    }

    /** Null leaves {@code sale_number} empty. */
    public void setSaleNumber(String saleNumber) {
        this.saleNumber = saleNumber;
    }

    public Optional<String> getProbeId() {
        return Optional.ofNullable(probeId);
    }

    /** Null leaves {@code probe_id} empty. */
    public void setProbeId(String probeId) {
        this.probeId = probeId;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public BigDecimal getCredit() {
        return credit;
    }

    public void setCredit(BigDecimal credit) {
        this.credit = credit;
    }

    public Optional<LocalDate> getClosedOn() {
        return Optional.ofNullable(closedOn);
    }

    /** Null leaves {@code closed_on} empty. */
    public void setClosedOn(LocalDate closedOn) {
        this.closedOn = closedOn;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    /** The actions taken, in the order they were taken. Read only; add one with {@link #addAction}. */
    public List<ComplaintActionRow> getActions() {
        return Collections.unmodifiableList(actions);
    }

    /** Replace the actions taken with {@code rows}; each of them belongs to this row from now on. */
    public void setActions(List<ComplaintActionRow> rows) {
        List<ComplaintActionRow> replacement = List.copyOf(rows);
        actions.clear();
        replacement.forEach(this::addAction);
    }

    /** Add one to the actions taken; it belongs to this row from now on. */
    public void addAction(ComplaintActionRow row) {
        row.setComplaint(this);
        actions.add(row);
    }

    @Override
    public String toString() {
        return reference + " " + outcome;
    }
}
