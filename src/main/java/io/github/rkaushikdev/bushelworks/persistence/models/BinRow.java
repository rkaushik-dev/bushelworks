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
 * One bin at one site, with what is in it.
 *
 * <p>A bin row is where grain physically is. The contents are a running blend and the sample
 * columns hold that blend, which is what a draw out of the bin will test.
 *
 * <p>Readings are rows of their own because the aeration log is a compliance record: the state asks
 * what the temperature was doing in the week before a load went out, and a single "last reading"
 * column cannot answer that.
 */
@Entity
@Table(
        name = "bin",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_bin_location_code",
                        columnNames = {"location_code", "bin_id"}),
        indexes = {@Index(name = "ix_bin_commodity_code", columnList = "commodity_code")})
@Check(name = "ck_bin_capacity_positive", constraints = "capacity_bushels > 0")
@Check(name = "ck_bin_bushels_not_negative", constraints = "bushels >= 0")
@Check(name = "ck_bin_bushels_fit", constraints = "bushels <= capacity_bushels")
public class BinRow extends Timestamped {

    @Column(name = "bin_id", length = 12, nullable = false)
    private String binId;

    @Column(name = "location_code", length = 12, nullable = false)
    private String locationCode;

    @Column(
            name = "capacity_bushels",
            precision = Columns.BUSHELS_PRECISION,
            scale = Columns.BUSHELS_SCALE,
            nullable = false)
    private BigDecimal capacityBushels;

    @Column(name = "bushels", precision = Columns.BUSHELS_PRECISION, scale = Columns.BUSHELS_SCALE, nullable = false)
    private BigDecimal bushels = BigDecimal.ZERO;

    @Column(name = "commodity_code", length = 8)
    private String commodityCode;

    @Column(name = "moisture", precision = Columns.PERCENT_PRECISION, scale = Columns.PERCENT_SCALE)
    private BigDecimal moisture;

    @Column(name = "test_weight", precision = Columns.PERCENT_PRECISION, scale = Columns.PERCENT_SCALE)
    private BigDecimal testWeight;

    @Column(name = "damage", precision = Columns.PERCENT_PRECISION, scale = Columns.PERCENT_SCALE)
    private BigDecimal damage;

    @Column(name = "is_aerated", nullable = false)
    private boolean aerated = false;

    @Column(name = "out_of_service", nullable = false)
    private boolean outOfService = false;

    @Column(name = "out_of_service_reason", length = 120)
    private String outOfServiceReason;

    @Column(name = "last_turned_on")
    private LocalDate lastTurnedOn;

    @OneToMany(mappedBy = "bin", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("takenAt")
    private List<BinReadingRow> readings = new ArrayList<>();

    public BinRow() {}

    public String getBinId() {
        return binId;
    }

    public void setBinId(String binId) {
        this.binId = binId;
    }

    public String getLocationCode() {
        return locationCode;
    }

    public void setLocationCode(String locationCode) {
        this.locationCode = locationCode;
    }

    public BigDecimal getCapacityBushels() {
        return capacityBushels;
    }

    public void setCapacityBushels(BigDecimal capacityBushels) {
        this.capacityBushels = capacityBushels;
    }

    public BigDecimal getBushels() {
        return bushels;
    }

    public void setBushels(BigDecimal bushels) {
        this.bushels = bushels;
    }

    public Optional<String> getCommodityCode() {
        return Optional.ofNullable(commodityCode);
    }

    /** Null leaves {@code commodity_code} empty. */
    public void setCommodityCode(String commodityCode) {
        this.commodityCode = commodityCode;
    }

    public Optional<BigDecimal> getMoisture() {
        return Optional.ofNullable(moisture);
    }

    /** Null leaves {@code moisture} empty. */
    public void setMoisture(BigDecimal moisture) {
        this.moisture = moisture;
    }

    public Optional<BigDecimal> getTestWeight() {
        return Optional.ofNullable(testWeight);
    }

    /** Null leaves {@code test_weight} empty. */
    public void setTestWeight(BigDecimal testWeight) {
        this.testWeight = testWeight;
    }

    public Optional<BigDecimal> getDamage() {
        return Optional.ofNullable(damage);
    }

    /** Null leaves {@code damage} empty. */
    public void setDamage(BigDecimal damage) {
        this.damage = damage;
    }

    public boolean isAerated() {
        return aerated;
    }

    public void setAerated(boolean aerated) {
        this.aerated = aerated;
    }

    public boolean isOutOfService() {
        return outOfService;
    }

    public void setOutOfService(boolean outOfService) {
        this.outOfService = outOfService;
    }

    public Optional<String> getOutOfServiceReason() {
        return Optional.ofNullable(outOfServiceReason);
    }

    /** Null leaves {@code out_of_service_reason} empty. */
    public void setOutOfServiceReason(String outOfServiceReason) {
        this.outOfServiceReason = outOfServiceReason;
    }

    public Optional<LocalDate> getLastTurnedOn() {
        return Optional.ofNullable(lastTurnedOn);
    }

    /** Null leaves {@code last_turned_on} empty. */
    public void setLastTurnedOn(LocalDate lastTurnedOn) {
        this.lastTurnedOn = lastTurnedOn;
    }

    /** The cable readings, oldest first. Read only; add one with {@link #addReading}. */
    public List<BinReadingRow> getReadings() {
        return Collections.unmodifiableList(readings);
    }

    /** Replace the cable readings with {@code rows}; each of them belongs to this row from now on. */
    public void setReadings(List<BinReadingRow> rows) {
        List<BinReadingRow> replacement = List.copyOf(rows);
        readings.clear();
        replacement.forEach(this::addReading);
    }

    /** Add one to the cable readings; it belongs to this row from now on. */
    public void addReading(BinReadingRow row) {
        row.setBin(this);
        readings.add(row);
    }

    /** Whether there is nothing in the bin. */
    public boolean isEmpty() {
        return bushels.signum() == 0;
    }

    /** What the bin will still take. */
    public BigDecimal getHeadroom() {
        return capacityBushels.subtract(bushels);
    }

    @Override
    public String toString() {
        return locationCode + "/" + binId;
    }
}
