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
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * One truck or car off a sale.
 *
 * <p>Destination weights and grades are empty until the buyer's settlement arrives, which is often
 * a fortnight behind the truck. A shipment with no destination weight yet is the normal state
 * rather than an error.
 */
@Entity
@Table(
        name = "shipment",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_shipment_sale_id",
                        columnNames = {"sale_id", "reference"}),
        indexes = {
            @Index(name = "ix_shipment_shipped_on", columnList = "shipped_on"),
            @Index(name = "ix_shipment_carrier_id", columnList = "carrier_id")
        })
@Check(name = "ck_shipment_origin_pounds_positive", constraints = "origin_pounds > 0")
public class ShipmentRow extends Timestamped {

    @Column(name = "reference", length = 24, nullable = false)
    private String reference;

    @Column(name = "shipped_on", nullable = false)
    private LocalDate shippedOn;

    @Column(name = "carrier_id", length = 24)
    private String carrierId;

    @Column(
            name = "origin_pounds",
            precision = Columns.POUNDS_PRECISION,
            scale = Columns.POUNDS_SCALE,
            nullable = false)
    private BigDecimal originPounds;

    @Column(
            name = "origin_bushels",
            precision = Columns.BUSHELS_PRECISION,
            scale = Columns.BUSHELS_SCALE,
            nullable = false)
    private BigDecimal originBushels;

    @Column(name = "destination_pounds", precision = Columns.POUNDS_PRECISION, scale = Columns.POUNDS_SCALE)
    private BigDecimal destinationPounds;

    @Column(name = "destination_bushels", precision = Columns.BUSHELS_PRECISION, scale = Columns.BUSHELS_SCALE)
    private BigDecimal destinationBushels;

    @Column(name = "destination_grade", length = 8)
    private String destinationGrade;

    @Column(name = "settled_on")
    private LocalDate settledOn;

    @Column(name = "proceeds", precision = Columns.MONEY_PRECISION, scale = Columns.MONEY_SCALE)
    private BigDecimal proceeds;

    @Column(name = "rejected", nullable = false)
    private boolean rejected = false;

    @Column(name = "rejection_reason", length = 200)
    private String rejectionReason;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "sale_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_shipment_sale_id_sale_contract"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private SaleRow sale;

    public ShipmentRow() {}

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public LocalDate getShippedOn() {
        return shippedOn;
    }

    public void setShippedOn(LocalDate shippedOn) {
        this.shippedOn = shippedOn;
    }

    public Optional<String> getCarrierId() {
        return Optional.ofNullable(carrierId);
    }

    /** Null leaves {@code carrier_id} empty. */
    public void setCarrierId(String carrierId) {
        this.carrierId = carrierId;
    }

    public BigDecimal getOriginPounds() {
        return originPounds;
    }

    public void setOriginPounds(BigDecimal originPounds) {
        this.originPounds = originPounds;
    }

    public BigDecimal getOriginBushels() {
        return originBushels;
    }

    public void setOriginBushels(BigDecimal originBushels) {
        this.originBushels = originBushels;
    }

    public Optional<BigDecimal> getDestinationPounds() {
        return Optional.ofNullable(destinationPounds);
    }

    /** Null leaves {@code destination_pounds} empty. */
    public void setDestinationPounds(BigDecimal destinationPounds) {
        this.destinationPounds = destinationPounds;
    }

    public Optional<BigDecimal> getDestinationBushels() {
        return Optional.ofNullable(destinationBushels);
    }

    /** Null leaves {@code destination_bushels} empty. */
    public void setDestinationBushels(BigDecimal destinationBushels) {
        this.destinationBushels = destinationBushels;
    }

    public Optional<String> getDestinationGrade() {
        return Optional.ofNullable(destinationGrade);
    }

    /** Null leaves {@code destination_grade} empty. */
    public void setDestinationGrade(String destinationGrade) {
        this.destinationGrade = destinationGrade;
    }

    public Optional<LocalDate> getSettledOn() {
        return Optional.ofNullable(settledOn);
    }

    /** Null leaves {@code settled_on} empty. */
    public void setSettledOn(LocalDate settledOn) {
        this.settledOn = settledOn;
    }

    public Optional<BigDecimal> getProceeds() {
        return Optional.ofNullable(proceeds);
    }

    /** Null leaves {@code proceeds} empty. */
    public void setProceeds(BigDecimal proceeds) {
        this.proceeds = proceeds;
    }

    public boolean isRejected() {
        return rejected;
    }

    public void setRejected(boolean rejected) {
        this.rejected = rejected;
    }

    public Optional<String> getRejectionReason() {
        return Optional.ofNullable(rejectionReason);
    }

    /** Null leaves {@code rejection_reason} empty. */
    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    /** The sale the load was shipped against. */
    public SaleRow getSale() {
        return sale;
    }

    void setSale(SaleRow sale) {
        this.sale = sale;
    }

    /** Whether the buyer's settlement for the load has come in. */
    public boolean isSettled() {
        return settledOn != null;
    }

    /**
     * Bushels that went missing between here and there.
     *
     * <p>Nothing until the destination weight arrives. A negative figure means the destination
     * weighed heavy, which happens and is not an error.
     */
    public Optional<BigDecimal> getTransitShrink() {
        return getDestinationBushels().map(originBushels::subtract);
    }
}
