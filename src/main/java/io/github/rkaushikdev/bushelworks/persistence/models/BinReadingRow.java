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
import java.time.OffsetDateTime;
import java.util.Optional;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** One temperature and moisture reading off a bin's cables. */
@Entity
@Table(
        name = "bin_reading",
        indexes = {@Index(name = "ix_bin_reading_taken_at", columnList = "taken_at")})
@Check(name = "ck_bin_reading_temperature_range", constraints = "temperature_c > -60 AND temperature_c < 90")
public class BinReadingRow extends Row {

    /** Above this many degrees the grain is working and somebody has to look at it. */
    public static final BigDecimal WARM = new BigDecimal("21");

    @Column(name = "taken_at", nullable = false)
    private OffsetDateTime takenAt;

    @Column(name = "cable", nullable = false)
    private int cable = 1;

    @Column(
            name = "temperature_c",
            precision = Columns.PERCENT_PRECISION,
            scale = Columns.PERCENT_SCALE,
            nullable = false)
    private BigDecimal temperatureC;

    @Column(name = "moisture", precision = Columns.PERCENT_PRECISION, scale = Columns.PERCENT_SCALE)
    private BigDecimal moisture;

    @Column(name = "fan_running", nullable = false)
    private boolean fanRunning = false;

    @Column(name = "note", length = 120)
    private String note;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bin_row_id", nullable = false, foreignKey = @ForeignKey(name = "fk_bin_reading_bin_row_id_bin"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private BinRow bin;

    public BinReadingRow() {}

    public OffsetDateTime getTakenAt() {
        return takenAt;
    }

    public void setTakenAt(OffsetDateTime takenAt) {
        this.takenAt = takenAt;
    }

    public int getCable() {
        return cable;
    }

    public void setCable(int cable) {
        this.cable = cable;
    }

    public BigDecimal getTemperatureC() {
        return temperatureC;
    }

    public void setTemperatureC(BigDecimal temperatureC) {
        this.temperatureC = temperatureC;
    }

    public Optional<BigDecimal> getMoisture() {
        return Optional.ofNullable(moisture);
    }

    /** Null leaves {@code moisture} empty. */
    public void setMoisture(BigDecimal moisture) {
        this.moisture = moisture;
    }

    public boolean isFanRunning() {
        return fanRunning;
    }

    public void setFanRunning(boolean fanRunning) {
        this.fanRunning = fanRunning;
    }

    public Optional<String> getNote() {
        return Optional.ofNullable(note);
    }

    /** Null leaves {@code note} empty. */
    public void setNote(String note) {
        this.note = note;
    }

    /** The bin the cables hang in. */
    public BinRow getBin() {
        return bin;
    }

    void setBin(BinRow bin) {
        this.bin = bin;
    }

    /** Whether the reading is above {@link #WARM}. */
    public boolean isWarm() {
        return temperatureC.compareTo(WARM) > 0;
    }
}
