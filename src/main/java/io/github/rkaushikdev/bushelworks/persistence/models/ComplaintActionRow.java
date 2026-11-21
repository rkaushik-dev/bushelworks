package io.github.rkaushikdev.bushelworks.persistence.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * What was done about a complaint, in the order it was done.
 *
 * <p>Kept apart from the complaint row because a complaint that took a fortnight and four telephone
 * calls to settle is one row with four actions, and flattening that into a note column loses who
 * did what and when.
 */
@Entity
@Table(
        name = "complaint_action",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_complaint_action_complaint_id",
                        columnNames = {"complaint_id", "sequence"}))
public class ComplaintActionRow extends Row {

    @Column(name = "sequence", nullable = false)
    private int sequence;

    @Column(name = "taken_on", nullable = false)
    private LocalDate takenOn;

    @Column(name = "taken_by", length = 40, nullable = false)
    private String takenBy;

    @Column(name = "what", length = 200, nullable = false)
    private String what;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "complaint_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_complaint_action_complaint_id_complaint"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ComplaintRow complaint;

    public ComplaintActionRow() {}

    public int getSequence() {
        return sequence;
    }

    public void setSequence(int sequence) {
        this.sequence = sequence;
    }

    public LocalDate getTakenOn() {
        return takenOn;
    }

    public void setTakenOn(LocalDate takenOn) {
        this.takenOn = takenOn;
    }

    public String getTakenBy() {
        return takenBy;
    }

    public void setTakenBy(String takenBy) {
        this.takenBy = takenBy;
    }

    public String getWhat() {
        return what;
    }

    public void setWhat(String what) {
        this.what = what;
    }

    /** The complaint it was done about. */
    public ComplaintRow getComplaint() {
        return complaint;
    }

    void setComplaint(ComplaintRow complaint) {
        this.complaint = complaint;
    }

    @Override
    public String toString() {
        return takenOn + " " + takenBy + ": " + what;
    }
}
