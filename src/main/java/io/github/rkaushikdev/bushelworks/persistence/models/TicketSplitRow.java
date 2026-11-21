package io.github.rkaushikdev.bushelworks.persistence.models;

import io.github.rkaushikdev.bushelworks.persistence.Columns;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.util.Optional;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** One party's share of one ticket, copied from the agreement at the window. */
@Entity
@Table(
        name = "ticket_split",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_ticket_split_ticket_id",
                        columnNames = {"ticket_id", "party_code"}))
@Check(name = "ck_ticket_split_percent_range", constraints = "percent > 0 AND percent <= 100")
public class TicketSplitRow extends Row {

    @Column(name = "party_code", length = 24, nullable = false)
    private String partyCode;

    @Column(name = "percent", precision = Columns.PERCENT_PRECISION, scale = Columns.PERCENT_SCALE, nullable = false)
    private BigDecimal percent;

    @Column(name = "role", length = 16, nullable = false)
    private String role = "owner";

    @Column(name = "bushels", precision = Columns.BUSHELS_PRECISION, scale = Columns.BUSHELS_SCALE)
    private BigDecimal bushels;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "ticket_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_ticket_split_ticket_id_scale_ticket"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ScaleTicketRow ticket;

    public TicketSplitRow() {}

    public String getPartyCode() {
        return partyCode;
    }

    public void setPartyCode(String partyCode) {
        this.partyCode = partyCode;
    }

    public BigDecimal getPercent() {
        return percent;
    }

    public void setPercent(BigDecimal percent) {
        this.percent = percent;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Optional<BigDecimal> getBushels() {
        return Optional.ofNullable(bushels);
    }

    /** Null leaves {@code bushels} empty. */
    public void setBushels(BigDecimal bushels) {
        this.bushels = bushels;
    }

    /** The ticket the share is of. */
    public ScaleTicketRow getTicket() {
        return ticket;
    }

    void setTicket(ScaleTicketRow ticket) {
        this.ticket = ticket;
    }
}
