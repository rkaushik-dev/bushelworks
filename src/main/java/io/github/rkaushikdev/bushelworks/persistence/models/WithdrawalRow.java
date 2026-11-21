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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** Bushels leaving a lot, with the day and the reason. */
@Entity
@Table(
        name = "storage_withdrawal",
        indexes = {@Index(name = "ix_storage_withdrawal_withdrawn_on", columnList = "withdrawn_on")})
@Check(name = "ck_storage_withdrawal_bushels_positive", constraints = "bushels > 0")
public class WithdrawalRow extends Timestamped {

    @Column(name = "bushels", precision = Columns.BUSHELS_PRECISION, scale = Columns.BUSHELS_SCALE, nullable = false)
    private BigDecimal bushels;

    @Column(name = "withdrawn_on", nullable = false)
    private LocalDate withdrawnOn;

    @Column(name = "reason", length = 24, nullable = false)
    private String reason = "settled";

    @Column(name = "settlement_number", length = 24)
    private String settlementNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "lot_row_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_storage_withdrawal_lot_row_id_storage_lot"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private StorageLotRow lot;

    public WithdrawalRow() {}

    public BigDecimal getBushels() {
        return bushels;
    }

    public void setBushels(BigDecimal bushels) {
        this.bushels = bushels;
    }

    public LocalDate getWithdrawnOn() {
        return withdrawnOn;
    }

    public void setWithdrawnOn(LocalDate withdrawnOn) {
        this.withdrawnOn = withdrawnOn;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Optional<String> getSettlementNumber() {
        return Optional.ofNullable(settlementNumber);
    }

    /** Null leaves {@code settlement_number} empty. */
    public void setSettlementNumber(String settlementNumber) {
        this.settlementNumber = settlementNumber;
    }

    /** The lot the bushels left. */
    public StorageLotRow getLot() {
        return lot;
    }

    void setLot(StorageLotRow lot) {
        this.lot = lot;
    }
}
