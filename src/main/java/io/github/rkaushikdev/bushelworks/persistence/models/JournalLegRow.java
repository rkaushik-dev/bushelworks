package io.github.rkaushikdev.bushelworks.persistence.models;

import io.github.rkaushikdev.bushelworks.persistence.Columns;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * One side of one entry.
 *
 * <p>A leg is a positive amount and a side, never a signed number, and the schema refuses anything
 * else even from a back fill that never went through the domain object. The sequence exists so the
 * legs come back in the order they were written: an entry read back with its credits before its
 * debits is still correct and still reads wrong to the person checking it.
 */
@Entity
@Table(
        name = "journal_leg",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_journal_leg_entry_id",
                        columnNames = {"entry_id", "sequence"}),
        indexes = {@Index(name = "ix_journal_leg_account_code", columnList = "account_code")})
@Check(name = "ck_journal_leg_leg_is_positive", constraints = "dollars > 0")
@Check(name = "ck_journal_leg_side_is_known", constraints = "side in ('debit', 'credit')")
public class JournalLegRow extends Row {

    @Column(name = "sequence", nullable = false)
    private int sequence;

    @Column(name = "account_code", length = 12, nullable = false)
    private String accountCode;

    @Column(name = "side", length = 6, nullable = false)
    private String side;

    @Column(name = "dollars", precision = Columns.MONEY_PRECISION, scale = Columns.MONEY_SCALE, nullable = false)
    private BigDecimal dollars;

    @Column(name = "memo", length = 120, nullable = false)
    private String memo = "";

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "entry_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_journal_leg_entry_id_journal_entry"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private JournalEntryRow entry;

    public JournalLegRow() {}

    public int getSequence() {
        return sequence;
    }

    public void setSequence(int sequence) {
        this.sequence = sequence;
    }

    public String getAccountCode() {
        return accountCode;
    }

    public void setAccountCode(String accountCode) {
        this.accountCode = accountCode;
    }

    public String getSide() {
        return side;
    }

    public void setSide(String side) {
        this.side = side;
    }

    public BigDecimal getDollars() {
        return dollars;
    }

    public void setDollars(BigDecimal dollars) {
        this.dollars = dollars;
    }

    public String getMemo() {
        return memo;
    }

    public void setMemo(String memo) {
        this.memo = memo;
    }

    /** The entry the leg is a side of. */
    public JournalEntryRow getEntry() {
        return entry;
    }

    void setEntry(JournalEntryRow entry) {
        this.entry = entry;
    }

    @Override
    public String toString() {
        return accountCode + " " + side + " " + dollars;
    }
}
