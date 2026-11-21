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
import java.time.LocalDate;
import java.util.Optional;
import org.hibernate.annotations.Check;

/**
 * A standing split between a landlord and a tenant on one farm.
 *
 * <p>The scale house does not want to ask for percentages at the window during harvest. The
 * agreement is on file, the ticket names the farm, and the split is copied onto the ticket at the
 * time so a later change to the agreement cannot move money on a settled load.
 */
@Entity
@Table(
        name = "split_agreement",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_split_agreement_farm_code",
                        columnNames = {"farm_code", "party_id", "effective_from"}))
@Check(name = "ck_split_agreement_percent_range", constraints = "percent > 0 AND percent <= 100")
public class SplitAgreement extends Timestamped {

    @Column(name = "farm_code", length = 24, nullable = false)
    private String farmCode;

    /** The party's key. */
    @Column(name = "party_id", nullable = false)
    private Integer partyId;

    @Column(name = "percent", precision = Columns.PERCENT_PRECISION, scale = Columns.PERCENT_SCALE, nullable = false)
    private BigDecimal percent;

    @Column(name = "role", length = 16, nullable = false)
    private String role = "owner";

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    /** Declares the foreign key on {@code party_id}. Written through the id column. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "party_id",
            insertable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_split_agreement_party_id_party"))
    private Party party;

    public SplitAgreement() {}

    public String getFarmCode() {
        return farmCode;
    }

    public void setFarmCode(String farmCode) {
        this.farmCode = farmCode;
    }

    public Integer getPartyId() {
        return partyId;
    }

    public void setPartyId(Integer partyId) {
        this.partyId = partyId;
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

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(LocalDate effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public Optional<LocalDate> getEffectiveTo() {
        return Optional.ofNullable(effectiveTo);
    }

    /** Null leaves {@code effective_to} empty. */
    public void setEffectiveTo(LocalDate effectiveTo) {
        this.effectiveTo = effectiveTo;
    }
}
