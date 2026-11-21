package io.github.rkaushikdev.bushelworks.persistence.models;

import io.github.rkaushikdev.bushelworks.persistence.Columns;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import org.hibernate.annotations.Check;

/** The commodity master as filed, so a new crop needs no deploy. */
@Entity
@Table(name = "commodity", uniqueConstraints = @UniqueConstraint(name = "uq_commodity_code", columnNames = "code"))
@Check(name = "ck_commodity_test_weight_positive", constraints = "standard_test_weight > 0")
@Check(name = "ck_commodity_base_moisture_range", constraints = "base_moisture > 0 AND base_moisture < 100")
public class CommodityRow extends Timestamped {

    @Column(name = "code", length = 8, nullable = false)
    private String code;

    @Column(name = "name", length = 60, nullable = false)
    private String name;

    @Column(
            name = "standard_test_weight",
            precision = Columns.PRICE_PRECISION,
            scale = Columns.PRICE_SCALE,
            nullable = false)
    private BigDecimal standardTestWeight;

    @Column(
            name = "base_moisture",
            precision = Columns.PERCENT_PRECISION,
            scale = Columns.PERCENT_SCALE,
            nullable = false)
    private BigDecimal baseMoisture;

    @Column(name = "price_unit", length = 4, nullable = false)
    private String priceUnit = "BU";

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    public CommodityRow() {}

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getStandardTestWeight() {
        return standardTestWeight;
    }

    public void setStandardTestWeight(BigDecimal standardTestWeight) {
        this.standardTestWeight = standardTestWeight;
    }

    public BigDecimal getBaseMoisture() {
        return baseMoisture;
    }

    public void setBaseMoisture(BigDecimal baseMoisture) {
        this.baseMoisture = baseMoisture;
    }

    public String getPriceUnit() {
        return priceUnit;
    }

    public void setPriceUnit(String priceUnit) {
        this.priceUnit = priceUnit;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    @Override
    public String toString() {
        return code + " " + name;
    }
}
