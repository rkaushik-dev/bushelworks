package io.github.rkaushikdev.bushelworks.persistence.models;

import io.github.rkaushikdev.bushelworks.persistence.Columns;
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
 * One purchase contract with whichever halves of its price are set.
 *
 * <p>A contract number is unique across the house, unlike a ticket number, and the bushels are
 * stored positive. The applications against it are read through {@link #getApplications()}; they
 * are written as rows of their own.
 */
@Entity
@Table(
        name = "purchase_contract",
        uniqueConstraints = @UniqueConstraint(name = "uq_purchase_contract_number", columnNames = "number"),
        indexes = {
            @Index(name = "ix_purchase_contract_party_code", columnList = "party_code, status"),
            @Index(name = "ix_purchase_contract_window_closes", columnList = "window_closes"),
            @Index(name = "ix_purchase_contract_open_window", columnList = "commodity_code, status, window_closes")
        })
@Check(name = "ck_purchase_contract_bushels_positive", constraints = "bushels > 0")
@Check(name = "ck_purchase_contract_window_ordered", constraints = "window_closes >= window_opens")
@Check(
        name = "ck_purchase_contract_tolerance_range",
        constraints = "tolerance_percent >= 0 AND tolerance_percent <= 25")
public class ContractRow extends Timestamped {

    @Column(name = "number", length = 24, nullable = false)
    private String number;

    @Column(name = "kind", length = 12, nullable = false)
    private String kind;

    @Column(name = "party_code", length = 24, nullable = false)
    private String partyCode;

    @Column(name = "commodity_code", length = 8, nullable = false)
    private String commodityCode;

    @Column(name = "location_code", length = 12, nullable = false)
    private String locationCode;

    @Column(name = "written_on", nullable = false)
    private LocalDate writtenOn;

    @Column(name = "bushels", precision = Columns.BUSHELS_PRECISION, scale = Columns.BUSHELS_SCALE, nullable = false)
    private BigDecimal bushels;

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
            name = "service_rate_per_bushel",
            precision = Columns.PRICE_PRECISION,
            scale = Columns.PRICE_SCALE,
            nullable = false)
    private BigDecimal serviceRatePerBushel = BigDecimal.ZERO;

    @Column(name = "status", length = 12, nullable = false)
    private String status = "open";

    @OneToMany(mappedBy = "contract")
    @OrderBy("id")
    private List<ApplicationRow> applications = new ArrayList<>();

    public ContractRow() {}

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

    public BigDecimal getServiceRatePerBushel() {
        return serviceRatePerBushel;
    }

    public void setServiceRatePerBushel(BigDecimal serviceRatePerBushel) {
        this.serviceRatePerBushel = serviceRatePerBushel;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    /** The bushels applied against this contract, reversed ones included, as read from the database. */
    public List<ApplicationRow> getApplications() {
        return Collections.unmodifiableList(applications);
    }

    @Override
    public String toString() {
        return number + " " + commodityCode + " " + bushels;
    }
}
