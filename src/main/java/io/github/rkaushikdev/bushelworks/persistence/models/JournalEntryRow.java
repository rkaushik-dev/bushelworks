package io.github.rkaushikdev.bushelworks.persistence.models;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * A balanced posting, with the thing it came from named.
 *
 * <p>The journal is append only. An entry that was wrong is corrected by posting its reverse, which
 * is why {@code reverses} points at another entry and why nothing here has a status that could be
 * flipped to void. An examiner who can watch a row change cannot rely on any of the rest of it.
 */
@Entity
@Table(
        name = "journal_entry",
        uniqueConstraints = @UniqueConstraint(name = "uq_journal_entry_number", columnNames = "number"),
        indexes = {
            @Index(name = "ix_journal_entry_posted_on", columnList = "posted_on, number"),
            @Index(name = "ix_journal_entry_source", columnList = "source, source_reference")
        })
public class JournalEntryRow extends Timestamped {

    @Column(name = "number", length = 24, nullable = false)
    private String number;

    @Column(name = "posted_on", nullable = false)
    private LocalDate postedOn;

    @Column(name = "memo", length = 200, nullable = false)
    private String memo = "";

    @Column(name = "source", length = 24, nullable = false)
    private String source = "";

    @Column(name = "source_reference", length = 48, nullable = false)
    private String sourceReference = "";

    /** The number of the entry this one reverses, if any. */
    @Column(name = "reverses", length = 24)
    private String reverses;

    @OneToMany(mappedBy = "entry", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sequence")
    private List<JournalLegRow> legs = new ArrayList<>();

    public JournalEntryRow() {}

    public String getNumber() {
        return number;
    }

    public void setNumber(String number) {
        this.number = number;
    }

    public LocalDate getPostedOn() {
        return postedOn;
    }

    public void setPostedOn(LocalDate postedOn) {
        this.postedOn = postedOn;
    }

    public String getMemo() {
        return memo;
    }

    public void setMemo(String memo) {
        this.memo = memo;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getSourceReference() {
        return sourceReference;
    }

    public void setSourceReference(String sourceReference) {
        this.sourceReference = sourceReference;
    }

    public Optional<String> getReverses() {
        return Optional.ofNullable(reverses);
    }

    /** Null leaves {@code reverses} empty. */
    public void setReverses(String reverses) {
        this.reverses = reverses;
    }

    /** The legs, in the order they were written. Read only; add one with {@link #addLeg}. */
    public List<JournalLegRow> getLegs() {
        return Collections.unmodifiableList(legs);
    }

    /** Replace the legs with {@code rows}; each of them belongs to this row from now on. */
    public void setLegs(List<JournalLegRow> rows) {
        List<JournalLegRow> replacement = List.copyOf(rows);
        legs.clear();
        replacement.forEach(this::addLeg);
    }

    /** Add one to the legs; it belongs to this row from now on. */
    public void addLeg(JournalLegRow row) {
        row.setEntry(this);
        legs.add(row);
    }

    @Override
    public String toString() {
        return number + " " + postedOn;
    }
}
