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
 * One sale out of the house.
 *
 * <p>A shipment is a row of its own rather than a running total on the sale, for the same reason a
 * storage withdrawal is: the buyer's settlement arrives weeks later and is reconciled car by car,
 * so the house has to be able to say which car and on what day.
 */
@Entity
@Table(
        name = "sale_contract",
        uniqueConstraints = @UniqueConstraint(name = "uq_sale_contract_number", columnNames = "number"),
        indexes = {
            @Index(name = "ix_sale_contract_buyer", columnList = "buyer, status"),
            @Index(name = "ix_sale_contract_commodity_code", columnList = "commodity_code, window_closes")
        })
@Check(name = "ck_sale_contract_bushels_positive", constraints = "bushels > 0")
@Check(name = "ck_sale_contract_shipped_not_negative", constraints = "shipped >= 0")
@Check(name = "ck_sale_contract_window_ordered", constraints = "window_closes >= window_opens")
@Check(name = "ck_sale_contract_freight_not_negative", constraints = "freight_per_bushel >= 0")
public class SaleRow extends Timestamped {

    @Column(name = "number", length = 24, nullable = false)
    private String number;

    @Column(name = "kind", length = 12, nullable = false)
    private String kind;

    @Column(name = "buyer", length = 80, nullable = false)
    private String buyer;

    @Column(name = "destination", length = 16, nullable = false)
    private String destination;

    @Column(name = "carrier", length = 8, nullable = false)
    private String carrier = "truck";

    @Column(name = "commodity_code", length = 8, nullable = false)
    private String commodityCode;

    @Column(name = "origin_code", length = 12, nullable = false)
    private String originCode;

    @Column(name = "written_on", nullable = false)
    private LocalDate writtenOn;

    @Column(name = "bushels", precision = Columns.BUSHELS_PRECISION, scale = Columns.BUSHELS_SCALE, nullable = false)
    private BigDecimal bushels;

    @Column(name = "shipped", precision = Columns.BUSHELS_PRECISION, scale = Columns.BUSHELS_SCALE, nullable = false)
    private BigDecimal shipped = BigDecimal.ZERO;

    @Column(name = "window_opens", nullable = false)
    private LocalDate windowOpens;

    @Column(name = "window_closes", nullable = false)
    private LocalDate windowCloses;

    @Column(name = "futures_symbol", length = 12)
    private String futuresSymbol;

    @Column(name = "futures_price", precision = Columns.PRICE_PRECISION, scale = Columns.PRICE_SCALE)
    private BigDecimal futuresPrice;

    @Column(name = "basis", precision = Columns.PRICE_PRECISION, scale = Columns.PRICE_SCALE)
    private BigDecimal basis;

    @Column(name = "flat_price", precision = Columns.PRICE_PRECISION, scale = Columns.PRICE_SCALE)
    private BigDecimal flatPrice;

    @Column(
            name = "tolerance_percent",
            precision = Columns.PERCENT_PRECISION,
            scale = Columns.PERCENT_SCALE,
            nullable = false)
    private BigDecimal tolerancePercent = new BigDecimal("2");

    @Column(
            name = "freight_per_bushel",
            precision = Columns.PRICE_PRECISION,
            scale = Columns.PRICE_SCALE,
            nullable = false)
    private BigDecimal freightPerBushel = BigDecimal.ZERO;

    @Column(
            name = "spec_max_moisture",
            precision = Columns.PERCENT_PRECISION,
            scale = Columns.PERCENT_SCALE,
            nullable = false)
    private BigDecimal specMaxMoisture;

    @Column(
            name = "spec_min_test_weight",
            precision = Columns.PERCENT_PRECISION,
            scale = Columns.PERCENT_SCALE,
            nullable = false)
    private BigDecimal specMinTestWeight;

    @Column(
            name = "spec_max_damage",
            precision = Columns.PERCENT_PRECISION,
            scale = Columns.PERCENT_SCALE,
            nullable = false)
    private BigDecimal specMaxDamage;

    @Column(
            name = "spec_max_foreign",
            precision = Columns.PERCENT_PRECISION,
            scale = Columns.PERCENT_SCALE,
            nullable = false)
    private BigDecimal specMaxForeign;

    @Column(name = "status", length = 12, nullable = false)
    private String status = "open";

    @OneToMany(mappedBy = "sale", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<ShipmentRow> shipments = new ArrayList<>();

    public SaleRow() {}

    public String getNumber() {
        return number;
    }

    public void setNumber(String number) {
        this.number = number;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getBuyer() {
        return buyer;
    }

    public void setBuyer(String buyer) {
        this.buyer = buyer;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public String getCarrier() {
        return carrier;
    }

    public void setCarrier(String carrier) {
        this.carrier = carrier;
    }

    public String getCommodityCode() {
        return commodityCode;
    }

    public void setCommodityCode(String commodityCode) {
        this.commodityCode = commodityCode;
    }

    public String getOriginCode() {
        return originCode;
    }

    public void setOriginCode(String originCode) {
        this.originCode = originCode;
    }

    public LocalDate getWrittenOn() {
        return writtenOn;
    }

    public void setWrittenOn(LocalDate writtenOn) {
        this.writtenOn = writtenOn;
    }

    public BigDecimal getBushels() {
        return bushels;
    }

    public void setBushels(BigDecimal bushels) {
        this.bushels = bushels;
    }

    public BigDecimal getShipped() {
        return shipped;
    }

    public void setShipped(BigDecimal shipped) {
        this.shipped = shipped;
    }

    public LocalDate getWindowOpens() {
        return windowOpens;
    }

    public void setWindowOpens(LocalDate windowOpens) {
        this.windowOpens = windowOpens;
    }

    public LocalDate getWindowCloses() {
        return windowCloses;
    }

    public void setWindowCloses(LocalDate windowCloses) {
        this.windowCloses = windowCloses;
    }

    public Optional<String> getFuturesSymbol() {
        return Optional.ofNullable(futuresSymbol);
    }

    /** Null leaves {@code futures_symbol} empty. */
    public void setFuturesSymbol(String futuresSymbol) {
        this.futuresSymbol = futuresSymbol;
    }

    public Optional<BigDecimal> getFuturesPrice() {
        return Optional.ofNullable(futuresPrice);
    }

    /** Null leaves {@code futures_price} empty. */
    public void setFuturesPrice(BigDecimal futuresPrice) {
        this.futuresPrice = futuresPrice;
    }

    public Optional<BigDecimal> getBasis() {
        return Optional.ofNullable(basis);
    }

    /** Null leaves {@code basis} empty. */
    public void setBasis(BigDecimal basis) {
        this.basis = basis;
    }

    public Optional<BigDecimal> getFlatPrice() {
        return Optional.ofNullable(flatPrice);
    }

    /** Null leaves {@code flat_price} empty. */
    public void setFlatPrice(BigDecimal flatPrice) {
        this.flatPrice = flatPrice;
    }

    public BigDecimal getTolerancePercent() {
        return tolerancePercent;
    }

    public void setTolerancePercent(BigDecimal tolerancePercent) {
        this.tolerancePercent = tolerancePercent;
    }

    public BigDecimal getFreightPerBushel() {
        return freightPerBushel;
    }

    public void setFreightPerBushel(BigDecimal freightPerBushel) {
        this.freightPerBushel = freightPerBushel;
    }

    public BigDecimal getSpecMaxMoisture() {
        return specMaxMoisture;
    }

    public void setSpecMaxMoisture(BigDecimal specMaxMoisture) {
        this.specMaxMoisture = specMaxMoisture;
    }

    public BigDecimal getSpecMinTestWeight() {
        return specMinTestWeight;
    }

    public void setSpecMinTestWeight(BigDecimal specMinTestWeight) {
        this.specMinTestWeight = specMinTestWeight;
    }

    public BigDecimal getSpecMaxDamage() {
        return specMaxDamage;
    }

    public void setSpecMaxDamage(BigDecimal specMaxDamage) {
        this.specMaxDamage = specMaxDamage;
    }

    public BigDecimal getSpecMaxForeign() {
        return specMaxForeign;
    }

    public void setSpecMaxForeign(BigDecimal specMaxForeign) {
        this.specMaxForeign = specMaxForeign;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    /** The loads shipped, truck by truck and car by car. Read only; add one with {@link #addShipment}. */
    public List<ShipmentRow> getShipments() {
        return Collections.unmodifiableList(shipments);
    }

    /** Replace the loads shipped with {@code rows}; each of them belongs to this row from now on. */
    public void setShipments(List<ShipmentRow> rows) {
        List<ShipmentRow> replacement = List.copyOf(rows);
        shipments.clear();
        replacement.forEach(this::addShipment);
    }

    /** Add one to the loads shipped; it belongs to this row from now on. */
    public void addShipment(ShipmentRow row) {
        row.setSale(this);
        shipments.add(row);
    }

    @Override
    public String toString() {
        return number + " " + buyer + " " + commodityCode;
    }
}
