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
import java.time.LocalDate;
import java.util.Optional;
import org.hibernate.annotations.Check;

/**
 * A negotiable receipt against stored grain.
 *
 * <p>Pre-numbered stock like a scale ticket, and the number is the document. A receipt is cancelled
 * rather than deleted, and the cancellation keeps the number so the sequence the inspector counts
 * stays whole.
 */
@Entity
@Table(
        name = "warehouse_receipt",
        uniqueConstraints =
                @UniqueConstraint(name = "uq_warehouse_receipt_receipt_number", columnNames = "receipt_number"),
        indexes = {@Index(name = "ix_warehouse_receipt_party_code", columnList = "party_code")})
@Check(name = "ck_warehouse_receipt_bushels_positive", constraints = "bushels > 0")
public class WarehouseReceiptRow extends Timestamped {

    @Column(name = "receipt_number", length = 24, nullable = false)
    private String receiptNumber;

    /** The key of the lot the receipt is written against. */
    @Column(name = "lot_row_id", nullable = false)
    private Integer lotRowId;

    @Column(name = "party_code", length = 24, nullable = false)
    private String partyCode;

    @Column(name = "commodity_code", length = 8, nullable = false)
    private String commodityCode;

    @Column(name = "location_code", length = 12, nullable = false)
    private String locationCode;

    @Column(name = "bushels", precision = Columns.BUSHELS_PRECISION, scale = Columns.BUSHELS_SCALE, nullable = false)
    private BigDecimal bushels;

    @Column(name = "grade", length = 8, nullable = false)
    private String grade;

    @Column(name = "issued_on", nullable = false)
    private LocalDate issuedOn;

    @Column(name = "cancelled_on")
    private LocalDate cancelledOn;

    @Column(name = "lienholder", length = 60)
    private String lienholder;

    /** Declares the foreign key on {@code lot_row_id}. Written through the id column. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "lot_row_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_warehouse_receipt_lot_row_id_storage_lot"))
    private StorageLotRow lot;

    public WarehouseReceiptRow() {}

    public String getReceiptNumber() {
        return receiptNumber;
    }

    public void setReceiptNumber(String receiptNumber) {
        this.receiptNumber = receiptNumber;
    }

    public Integer getLotRowId() {
        return lotRowId;
    }

    public void setLotRowId(Integer lotRowId) {
        this.lotRowId = lotRowId;
    }

    public String getPartyCode() {
        return partyCode;
    }

    public void setPartyCode(String partyCode) {
        this.partyCode = partyCode;
    }

    public String getCommodityCode() {
        return commodityCode;
    }

    public void setCommodityCode(String commodityCode) {
        this.commodityCode = commodityCode;
    }

    public String getLocationCode() {
        return locationCode;
    }

    public void setLocationCode(String locationCode) {
        this.locationCode = locationCode;
    }

    public BigDecimal getBushels() {
        return bushels;
    }

    public void setBushels(BigDecimal bushels) {
        this.bushels = bushels;
    }

    public String getGrade() {
        return grade;
    }

    public void setGrade(String grade) {
        this.grade = grade;
    }

    public LocalDate getIssuedOn() {
        return issuedOn;
    }

    public void setIssuedOn(LocalDate issuedOn) {
        this.issuedOn = issuedOn;
    }

    public Optional<LocalDate> getCancelledOn() {
        return Optional.ofNullable(cancelledOn);
    }

    /** Null leaves {@code cancelled_on} empty. */
    public void setCancelledOn(LocalDate cancelledOn) {
        this.cancelledOn = cancelledOn;
    }

    public Optional<String> getLienholder() {
        return Optional.ofNullable(lienholder);
    }

    /** Null leaves {@code lienholder} empty. */
    public void setLienholder(String lienholder) {
        this.lienholder = lienholder;
    }

    /** Whether the receipt still stands: it has not been cancelled. */
    public boolean isLive() {
        return cancelledOn == null;
    }
}
