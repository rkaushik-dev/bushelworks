package io.github.rkaushikdev.bushelworks.persistence.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** Which tickets a settlement covers. */
@Entity
@Table(
        name = "settlement_ticket",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_settlement_ticket_settlement_id",
                        columnNames = {"settlement_id", "ticket_id"}))
public class SettlementTicketRow extends Row {

    /** The settlement's key. */
    @Column(name = "settlement_id", nullable = false)
    private Integer settlementId;

    /** The ticket's key. */
    @Column(name = "ticket_id", nullable = false)
    private Integer ticketId;

    /** Declares the foreign key on {@code settlement_id}. Written through the id column. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "settlement_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_settlement_ticket_settlement_id_settlement"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private SettlementRow settlement;

    /** Declares the foreign key on {@code ticket_id}. Written through the id column. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "ticket_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_settlement_ticket_ticket_id_scale_ticket"))
    private ScaleTicketRow ticket;

    public SettlementTicketRow() {}

    public Integer getSettlementId() {
        return settlementId;
    }

    public void setSettlementId(Integer settlementId) {
        this.settlementId = settlementId;
    }

    public Integer getTicketId() {
        return ticketId;
    }

    public void setTicketId(Integer ticketId) {
        this.ticketId = ticketId;
    }
}
