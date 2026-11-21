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
 * One load across the scale, exactly as the scale head reported it.
 *
 * <p>A ticket is append only. There is no update path in the repository and no deleted flag: a
 * mistake is corrected by writing a void and then a replacement, and both rows stay. The number is
 * unique per location rather than globally, because each scale house runs its own book of
 * pre-numbered stock.
 *
 * <p>The probe sample is a child table rather than a column per factor. Wheat records nine factors
 * and sunflowers record three, and a wide table of mostly empty columns cannot tell "not tested"
 * from "tested at zero", which is a distinction the grade ladder depends on.
 */
@Entity
@Table(
        name = "scale_ticket",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_scale_ticket_location_code",
                        columnNames = {"location_code", "ticket_number"}),
        indexes = {
            @Index(name = "ix_scale_ticket_scale_date", columnList = "scale_date"),
            @Index(name = "ix_scale_ticket_party_code", columnList = "party_code"),
            @Index(name = "ix_scale_ticket_commodity_code", columnList = "commodity_code, scale_date"),
            @Index(name = "ix_scale_ticket_ticket_number", columnList = "ticket_number"),
            @Index(name = "ix_scale_ticket_waiting", columnList = "location_code, settled, voided, scale_date")
        })
@Check(name = "ck_scale_ticket_gross_at_least_tare", constraints = "gross_pounds >= tare_pounds")
@Check(name = "ck_scale_ticket_tare_not_negative", constraints = "tare_pounds >= 0")
@Check(name = "ck_scale_ticket_dockage_range", constraints = "dockage_percent >= 0 AND dockage_percent < 100")
public class ScaleTicketRow extends Timestamped {

    @Column(name = "ticket_number", length = 24, nullable = false)
    private String ticketNumber;

    @Column(name = "location_code", length = 12, nullable = false)
    private String locationCode;

    @Column(name = "scale_date", nullable = false)
    private LocalDate scaleDate;

    @Column(name = "commodity_code", length = 8, nullable = false)
    private String commodityCode;

    @Column(name = "party_code", length = 24, nullable = false)
    private String partyCode;

    @Column(name = "gross_pounds", precision = Columns.POUNDS_PRECISION, scale = Columns.POUNDS_SCALE, nullable = false)
    private BigDecimal grossPounds;

    @Column(name = "tare_pounds", precision = Columns.POUNDS_PRECISION, scale = Columns.POUNDS_SCALE, nullable = false)
    private BigDecimal tarePounds;

    @Column(
            name = "dockage_percent",
            precision = Columns.PERCENT_PRECISION,
            scale = Columns.PERCENT_SCALE,
            nullable = false)
    private BigDecimal dockagePercent = BigDecimal.ZERO;

    @Column(name = "disposition", length = 16, nullable = false)
    private String disposition;

    @Column(name = "contract_number", length = 24)
    private String contractNumber;

    @Column(name = "truck_id", length = 16)
    private String truckId;

    @Column(name = "driver", length = 60)
    private String driver;

    @Column(name = "farm", length = 60)
    private String farm;

    @Column(name = "field_name", length = 60)
    private String fieldName;

    @Column(name = "remarks", length = 200)
    private String remarks;

    @Column(name = "voided", nullable = false)
    private boolean voided = false;

    @Column(name = "settled", nullable = false)
    private boolean settled = false;

    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<SampleRow> samples = new ArrayList<>();

    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<TicketSplitRow> splits = new ArrayList<>();

    public ScaleTicketRow() {}

    public String getTicketNumber() {
        return ticketNumber;
    }

    public void setTicketNumber(String ticketNumber) {
        this.ticketNumber = ticketNumber;
    }

    public String getLocationCode() {
        return locationCode;
    }

    public void setLocationCode(String locationCode) {
        this.locationCode = locationCode;
    }

    public LocalDate getScaleDate() {
        return scaleDate;
    }

    public void setScaleDate(LocalDate scaleDate) {
        this.scaleDate = scaleDate;
    }

    public String getCommodityCode() {
        return commodityCode;
    }

    public void setCommodityCode(String commodityCode) {
        this.commodityCode = commodityCode;
    }

    public String getPartyCode() {
        return partyCode;
    }

    public void setPartyCode(String partyCode) {
        this.partyCode = partyCode;
    }

    public BigDecimal getGrossPounds() {
        return grossPounds;
    }

    public void setGrossPounds(BigDecimal grossPounds) {
        this.grossPounds = grossPounds;
    }

    public BigDecimal getTarePounds() {
        return tarePounds;
    }

    public void setTarePounds(BigDecimal tarePounds) {
        this.tarePounds = tarePounds;
    }

    public BigDecimal getDockagePercent() {
        return dockagePercent;
    }

    public void setDockagePercent(BigDecimal dockagePercent) {
        this.dockagePercent = dockagePercent;
    }

    public String getDisposition() {
        return disposition;
    }

    public void setDisposition(String disposition) {
        this.disposition = disposition;
    }

    public Optional<String> getContractNumber() {
        return Optional.ofNullable(contractNumber);
    }

    /** Null leaves {@code contract_number} empty. */
    public void setContractNumber(String contractNumber) {
        this.contractNumber = contractNumber;
    }

    public Optional<String> getTruckId() {
        return Optional.ofNullable(truckId);
    }

    /** Null leaves {@code truck_id} empty. */
    public void setTruckId(String truckId) {
        this.truckId = truckId;
    }

    public Optional<String> getDriver() {
        return Optional.ofNullable(driver);
    }

    /** Null leaves {@code driver} empty. */
    public void setDriver(String driver) {
        this.driver = driver;
    }

    public Optional<String> getFarm() {
        return Optional.ofNullable(farm);
    }

    /** Null leaves {@code farm} empty. */
    public void setFarm(String farm) {
        this.farm = farm;
    }

    public Optional<String> getFieldName() {
        return Optional.ofNullable(fieldName);
    }

    /** Null leaves {@code field_name} empty. */
    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public Optional<String> getRemarks() {
        return Optional.ofNullable(remarks);
    }

    /** Null leaves {@code remarks} empty. */
    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }

    public boolean isVoided() {
        return voided;
    }

    public void setVoided(boolean voided) {
        this.voided = voided;
    }

    public boolean isSettled() {
        return settled;
    }

    public void setSettled(boolean settled) {
        this.settled = settled;
    }

    /** The probe readings, one per factor. Read only; add one with {@link #addSample}. */
    public List<SampleRow> getSamples() {
        return Collections.unmodifiableList(samples);
    }

    /** Replace the probe readings with {@code rows}; each of them belongs to this row from now on. */
    public void setSamples(List<SampleRow> rows) {
        List<SampleRow> replacement = List.copyOf(rows);
        samples.clear();
        replacement.forEach(this::addSample);
    }

    /** Add one to the probe readings; it belongs to this row from now on. */
    public void addSample(SampleRow row) {
        row.setTicket(this);
        samples.add(row);
    }

    /** The shares, as copied at the window. Read only; add one with {@link #addSplit}. */
    public List<TicketSplitRow> getSplits() {
        return Collections.unmodifiableList(splits);
    }

    /** Replace the shares with {@code rows}; each of them belongs to this row from now on. */
    public void setSplits(List<TicketSplitRow> rows) {
        List<TicketSplitRow> replacement = List.copyOf(rows);
        splits.clear();
        replacement.forEach(this::addSplit);
    }

    /** Add one to the shares; it belongs to this row from now on. */
    public void addSplit(TicketSplitRow row) {
        row.setTicket(this);
        splits.add(row);
    }

    /** Gross less tare. */
    public BigDecimal getNetPounds() {
        return grossPounds.subtract(tarePounds);
    }

    @Override
    public String toString() {
        return locationCode + "/" + ticketNumber;
    }
}
