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

/**
 * A finished statement, with its lines.
 *
 * <p>A settlement number is unique across the house, unlike a ticket number, since the cheque run
 * keys on it.
 */
@Entity
@Table(
        name = "settlement",
        uniqueConstraints = @UniqueConstraint(name = "uq_settlement_number", columnNames = "number"),
        indexes = {
            @Index(name = "ix_settlement_party_code", columnList = "party_code, settled_on"),
            @Index(name = "ix_settlement_settled_on", columnList = "settled_on"),
            @Index(name = "ix_settlement_live_by_site", columnList = "location_code, voided, settled_on")
        })
public class SettlementRow extends Timestamped {

    @Column(name = "number", length = 24, nullable = false)
    private String number;

    @Column(name = "settled_on", nullable = false)
    private LocalDate settledOn;

    @Column(name = "party_code", length = 24, nullable = false)
    private String partyCode;

    @Column(name = "location_code", length = 12, nullable = false)
    private String locationCode;

    @Column(name = "gross", precision = Columns.MONEY_PRECISION, scale = Columns.MONEY_SCALE, nullable = false)
    private BigDecimal gross;

    @Column(name = "deductions", precision = Columns.MONEY_PRECISION, scale = Columns.MONEY_SCALE, nullable = false)
    private BigDecimal deductions;

    @Column(name = "net", precision = Columns.MONEY_PRECISION, scale = Columns.MONEY_SCALE, nullable = false)
    private BigDecimal net;

    @Column(name = "paid_on")
    private LocalDate paidOn;

    @Column(name = "cheque_number", length = 16)
    private String chequeNumber;

    @Column(name = "voided", nullable = false)
    private boolean voided = false;

    @OneToMany(mappedBy = "settlement", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sequence")
    private List<SettlementLineRow> lines = new ArrayList<>();

    public SettlementRow() {}

    public String getNumber() {
        return number;
    }

    public void setNumber(String number) {
        this.number = number;
    }

    public LocalDate getSettledOn() {
        return settledOn;
    }

    public void setSettledOn(LocalDate settledOn) {
        this.settledOn = settledOn;
    }

    public String getPartyCode() {
        return partyCode;
    }

    public void setPartyCode(String partyCode) {
        this.partyCode = partyCode;
    }

    public String getLocationCode() {
        return locationCode;
    }

    public void setLocationCode(String locationCode) {
        this.locationCode = locationCode;
    }

    public BigDecimal getGross() {
        return gross;
    }

    public void setGross(BigDecimal gross) {
        this.gross = gross;
    }

    public BigDecimal getDeductions() {
        return deductions;
    }

    public void setDeductions(BigDecimal deductions) {
        this.deductions = deductions;
    }

    public BigDecimal getNet() {
        return net;
    }

    public void setNet(BigDecimal net) {
        this.net = net;
    }

    public Optional<LocalDate> getPaidOn() {
        return Optional.ofNullable(paidOn);
    }

    /** Null leaves {@code paid_on} empty. */
    public void setPaidOn(LocalDate paidOn) {
        this.paidOn = paidOn;
    }

    public Optional<String> getChequeNumber() {
        return Optional.ofNullable(chequeNumber);
    }

    /** Null leaves {@code cheque_number} empty. */
    public void setChequeNumber(String chequeNumber) {
        this.chequeNumber = chequeNumber;
    }

    public boolean isVoided() {
        return voided;
    }

    public void setVoided(boolean voided) {
        this.voided = voided;
    }

    /** The printed lines, in sequence. Read only; add one with {@link #addLine}. */
    public List<SettlementLineRow> getLines() {
        return Collections.unmodifiableList(lines);
    }

    /** Replace the printed lines with {@code rows}; each of them belongs to this row from now on. */
    public void setLines(List<SettlementLineRow> rows) {
        List<SettlementLineRow> replacement = List.copyOf(rows);
        lines.clear();
        replacement.forEach(this::addLine);
    }

    /** Add one to the printed lines; it belongs to this row from now on. */
    public void addLine(SettlementLineRow row) {
        row.setSettlement(this);
        lines.add(row);
    }

    @Override
    public String toString() {
        return number + " " + partyCode + " " + net;
    }
}
