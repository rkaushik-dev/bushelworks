package io.github.rkaushikdev.bushelworks.persistence.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.util.Optional;
import org.hibernate.annotations.Check;

/**
 * An accounting month, and whether anything can still be posted into it.
 *
 * <p>The house closes a month when the return goes to the state, and a ticket keyed late after that
 * has to land in the open month instead. Without a row saying so, the late ticket posts into a
 * period whose figures have already been filed and nobody finds out until the next return does not
 * tie back.
 */
@Entity
@Table(
        name = "ledger_period",
        uniqueConstraints = @UniqueConstraint(name = "uq_ledger_period_code", columnNames = "code"),
        indexes = {@Index(name = "ix_ledger_period_starts_on", columnList = "starts_on")})
@Check(name = "ck_ledger_period_period_runs_forwards", constraints = "ends_on >= starts_on")
public class PeriodRow extends Timestamped {

    @Column(name = "code", length = 7, nullable = false)
    private String code;

    @Column(name = "starts_on", nullable = false)
    private LocalDate startsOn;

    @Column(name = "ends_on", nullable = false)
    private LocalDate endsOn;

    @Column(name = "closed", nullable = false)
    private boolean closed = false;

    @Column(name = "closed_on")
    private LocalDate closedOn;

    @Column(name = "closed_by", length = 48, nullable = false)
    private String closedBy = "";

    public PeriodRow() {}

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public LocalDate getStartsOn() {
        return startsOn;
    }

    public void setStartsOn(LocalDate startsOn) {
        this.startsOn = startsOn;
    }

    public LocalDate getEndsOn() {
        return endsOn;
    }

    public void setEndsOn(LocalDate endsOn) {
        this.endsOn = endsOn;
    }

    public boolean isClosed() {
        return closed;
    }

    public void setClosed(boolean closed) {
        this.closed = closed;
    }

    public Optional<LocalDate> getClosedOn() {
        return Optional.ofNullable(closedOn);
    }

    /** Null leaves {@code closed_on} empty. */
    public void setClosedOn(LocalDate closedOn) {
        this.closedOn = closedOn;
    }

    public String getClosedBy() {
        return closedBy;
    }

    public void setClosedBy(String closedBy) {
        this.closedBy = closedBy;
    }

    @Override
    public String toString() {
        return code + " " + (closed ? "closed" : "open");
    }
}
