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
 * Bushels off one ticket landing on one contract.
 *
 * <p>An application cannot exist without both a ticket and a contract, and the schema says so
 * rather than only the service layer.
 */
@Entity
@Table(
        name = "contract_application",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_contract_application_ticket_id",
                        columnNames = {"ticket_id", "contract_id"}),
        indexes = {
            @Index(name = "ix_contract_application_applied_on", columnList = "applied_on"),
            @Index(name = "ix_contract_application_contract_live", columnList = "contract_id, reversed_on")
        })
@Check(name = "ck_contract_application_bushels_positive", constraints = "bushels > 0")
public class ApplicationRow extends Timestamped {

    /** The ticket's key. */
    @Column(name = "ticket_id", nullable = false)
    private Integer ticketId;

    /** The contract's key. */
    @Column(name = "contract_id", nullable = false)
    private Integer contractId;

    @Column(name = "bushels", precision = Columns.BUSHELS_PRECISION, scale = Columns.BUSHELS_SCALE, nullable = false)
    private BigDecimal bushels;

    @Column(name = "applied_on", nullable = false)
    private LocalDate appliedOn;

    @Column(name = "reversed_on")
    private LocalDate reversedOn;

    /** Declares the foreign key on {@code ticket_id}. Written through the id column. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "ticket_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_contract_application_ticket_id_scale_ticket"))
    private ScaleTicketRow ticket;

    /** Declares the foreign key on {@code contract_id}. Written through the id column. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "contract_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_contract_application_contract_id_purchase_contract"))
    private ContractRow contract;

    public ApplicationRow() {}

    public Integer getTicketId() {
        return ticketId;
    }

    public void setTicketId(Integer ticketId) {
        this.ticketId = ticketId;
    }

    public Integer getContractId() {
        return contractId;
    }

    public void setContractId(Integer contractId) {
        this.contractId = contractId;
    }

    public BigDecimal getBushels() {
        return bushels;
    }

    public void setBushels(BigDecimal bushels) {
        this.bushels = bushels;
    }

    public LocalDate getAppliedOn() {
        return appliedOn;
    }

    public void setAppliedOn(LocalDate appliedOn) {
        this.appliedOn = appliedOn;
    }

    public Optional<LocalDate> getReversedOn() {
        return Optional.ofNullable(reversedOn);
    }

    /** Null leaves {@code reversed_on} empty. */
    public void setReversedOn(LocalDate reversedOn) {
        this.reversedOn = reversedOn;
    }

    /** The contract, as read from the database; null on a row written in this session until it is read back. */
    public ContractRow getContract() {
        return contract;
    }
}
