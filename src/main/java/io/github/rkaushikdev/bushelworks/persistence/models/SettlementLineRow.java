package io.github.rkaushikdev.bushelworks.persistence.models;

import io.github.rkaushikdev.bushelworks.persistence.Columns;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.util.Optional;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * One printed row, stored as a positive magnitude plus a kind.
 *
 * <p>Never a signed amount, so a sign convention cannot drift between the writer and the reader.
 */
@Entity
@Table(
        name = "settlement_line",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_settlement_line_settlement_id",
                        columnNames = {"settlement_id", "sequence"}))
@Check(name = "ck_settlement_line_dollars_not_negative", constraints = "dollars >= 0")
public class SettlementLineRow extends Row {

    @Column(name = "sequence", nullable = false)
    private int sequence;

    @Column(name = "kind", length = 16, nullable = false)
    private String kind;

    @Column(name = "code", length = 24, nullable = false)
    private String code;

    @Column(name = "description", length = 120, nullable = false)
    private String description;

    @Column(name = "dollars", precision = Columns.MONEY_PRECISION, scale = Columns.MONEY_SCALE, nullable = false)
    private BigDecimal dollars;

    @Column(name = "quantity", precision = Columns.BUSHELS_PRECISION, scale = Columns.BUSHELS_SCALE)
    private BigDecimal quantity;

    @Column(name = "rate", precision = Columns.PRICE_PRECISION, scale = Columns.PRICE_SCALE)
    private BigDecimal rate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "settlement_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_settlement_line_settlement_id_settlement"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private SettlementRow settlement;

    public SettlementLineRow() {}

    public int getSequence() {
        return sequence;
    }

    public void setSequence(int sequence) {
        this.sequence = sequence;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getDollars() {
        return dollars;
    }

    public void setDollars(BigDecimal dollars) {
        this.dollars = dollars;
    }

    public Optional<BigDecimal> getQuantity() {
        return Optional.ofNullable(quantity);
    }

    /** Null leaves {@code quantity} empty. */
    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public Optional<BigDecimal> getRate() {
        return Optional.ofNullable(rate);
    }

    /** Null leaves {@code rate} empty. */
    public void setRate(BigDecimal rate) {
        this.rate = rate;
    }

    /** The statement the line is printed on. */
    public SettlementRow getSettlement() {
        return settlement;
    }

    void setSettlement(SettlementRow settlement) {
        this.settlement = settlement;
    }
}
