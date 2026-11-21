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

/** One factor reading off the probe sample. */
@Entity
@Table(
        name = "ticket_sample",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_ticket_sample_ticket_id",
                        columnNames = {"ticket_id", "factor"}))
@Check(name = "ck_ticket_sample_value_not_negative", constraints = "value >= 0")
public class SampleRow extends Row {

    @Column(name = "factor", length = 24, nullable = false)
    private String factor;

    @Column(name = "value", precision = Columns.PERCENT_PRECISION, scale = Columns.PERCENT_SCALE, nullable = false)
    private BigDecimal value;

    @Column(name = "instrument", length = 24)
    private String instrument;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "ticket_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_ticket_sample_ticket_id_scale_ticket"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private ScaleTicketRow ticket;

    public SampleRow() {}

    public String getFactor() {
        return factor;
    }

    public void setFactor(String factor) {
        this.factor = factor;
    }

    public BigDecimal getValue() {
        return value;
    }

    public void setValue(BigDecimal value) {
        this.value = value;
    }

    public Optional<String> getInstrument() {
        return Optional.ofNullable(instrument);
    }

    /** Null leaves {@code instrument} empty. */
    public void setInstrument(String instrument) {
        this.instrument = instrument;
    }

    /** The ticket the reading was taken off. */
    public ScaleTicketRow getTicket() {
        return ticket;
    }

    void setTicket(ScaleTicketRow ticket) {
        this.ticket = ticket;
    }
}
