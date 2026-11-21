package io.github.rkaushikdev.bushelworks.persistence.models;

import io.github.rkaushikdev.bushelworks.persistence.Columns;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
 * Bushels received into storage on one day for one producer.
 *
 * <p>A storage lot is grain the house is holding that belongs to somebody else. The warehouse
 * licence turns on being able to say, on any day, whose bushels are in which bin and what they are
 * owed, so these rows are the ones the state inspector reads.
 *
 * <p>Withdrawals are rows of their own rather than a running balance on the lot. The inspector asks
 * when bushels left, not only how many are left, and a balance column cannot answer that.
 */
@Entity
@Table(
        name = "storage_lot",
        uniqueConstraints = @UniqueConstraint(name = "uq_storage_lot_lot_id", columnNames = "lot_id"),
        indexes = {
            @Index(name = "ix_storage_lot_party_code", columnList = "party_code, commodity_code"),
            @Index(name = "ix_storage_lot_stored_on", columnList = "stored_on")
        })
@Check(name = "ck_storage_lot_bushels_positive", constraints = "bushels > 0")
public class StorageLotRow extends Timestamped {

    @Column(name = "lot_id", length = 24, nullable = false)
    private String lotId;

    @Column(name = "party_code", length = 24, nullable = false)
    private String partyCode;

    @Column(name = "commodity_code", length = 8, nullable = false)
    private String commodityCode;

    @Column(name = "location_code", length = 12, nullable = false)
    private String locationCode;

    @Column(name = "bushels", precision = Columns.BUSHELS_PRECISION, scale = Columns.BUSHELS_SCALE, nullable = false)
    private BigDecimal bushels;

    @Column(name = "stored_on", nullable = false)
    private LocalDate storedOn;

    /** The key of the ticket the lot came in on, if any. */
    @Column(name = "ticket_id")
    private Integer ticketId;

    @Column(name = "closed_on")
    private LocalDate closedOn;

    @Column(name = "billed_through")
    private LocalDate billedThrough;

    @Column(
            name = "accrued_to_date",
            precision = Columns.MONEY_PRECISION,
            scale = Columns.MONEY_SCALE,
            nullable = false)
    private BigDecimal accruedToDate = new BigDecimal("0.00");

    @Column(name = "is_deferred_price", nullable = false)
    private boolean deferredPrice = false;

    @OneToMany(mappedBy = "lot", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<WithdrawalRow> withdrawals = new ArrayList<>();

    /** Declares the foreign key on {@code ticket_id}. Written through the id column. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "ticket_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_storage_lot_ticket_id_scale_ticket"))
    private ScaleTicketRow ticket;

    public StorageLotRow() {}

    public String getLotId() {
        return lotId;
    }

    public void setLotId(String lotId) {
        this.lotId = lotId;
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

    public LocalDate getStoredOn() {
        return storedOn;
    }

    public void setStoredOn(LocalDate storedOn) {
        this.storedOn = storedOn;
    }

    public Optional<Integer> getTicketId() {
        return Optional.ofNullable(ticketId);
    }

    /** Null leaves {@code ticket_id} empty. */
    public void setTicketId(Integer ticketId) {
        this.ticketId = ticketId;
    }

    public Optional<LocalDate> getClosedOn() {
        return Optional.ofNullable(closedOn);
    }

    /** Null leaves {@code closed_on} empty. */
    public void setClosedOn(LocalDate closedOn) {
        this.closedOn = closedOn;
    }

    public Optional<LocalDate> getBilledThrough() {
        return Optional.ofNullable(billedThrough);
    }

    /** Null leaves {@code billed_through} empty. */
    public void setBilledThrough(LocalDate billedThrough) {
        this.billedThrough = billedThrough;
    }

    public BigDecimal getAccruedToDate() {
        return accruedToDate;
    }

    public void setAccruedToDate(BigDecimal accruedToDate) {
        this.accruedToDate = accruedToDate;
    }

    public boolean isDeferredPrice() {
        return deferredPrice;
    }

    public void setDeferredPrice(boolean deferredPrice) {
        this.deferredPrice = deferredPrice;
    }

    /** The withdrawals, in the order they were taken. Read only; add one with {@link #addWithdrawal}. */
    public List<WithdrawalRow> getWithdrawals() {
        return Collections.unmodifiableList(withdrawals);
    }

    /** Replace the withdrawals with {@code rows}; each of them belongs to this row from now on. */
    public void setWithdrawals(List<WithdrawalRow> rows) {
        List<WithdrawalRow> replacement = List.copyOf(rows);
        withdrawals.clear();
        replacement.forEach(this::addWithdrawal);
    }

    /** Add one to the withdrawals; it belongs to this row from now on. */
    public void addWithdrawal(WithdrawalRow row) {
        row.setLot(this);
        withdrawals.add(row);
    }

    /** Every bushel withdrawn so far. */
    public BigDecimal getWithdrawn() {
        BigDecimal total = BigDecimal.ZERO;
        for (WithdrawalRow withdrawal : withdrawals) {
            total = total.add(withdrawal.getBushels());
        }
        return total;
    }

    /** What is still in the lot. */
    public BigDecimal getOpenBushels() {
        return bushels.subtract(getWithdrawn());
    }

    @Override
    public String toString() {
        return lotId + " " + partyCode + " " + commodityCode;
    }
}
