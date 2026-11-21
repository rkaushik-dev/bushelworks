package io.github.rkaushikdev.bushelworks.persistence.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Optional;
import org.hibernate.annotations.Check;

/** One elevator. The house may have several and they file separately. */
@Entity
@Table(name = "location", uniqueConstraints = @UniqueConstraint(name = "uq_location_code", columnNames = "code"))
@Check(name = "ck_location_code_not_blank", constraints = "length(code) > 0")
public class Location extends Timestamped {

    @Column(name = "code", length = 12, nullable = false)
    private String code;

    @Column(name = "name", length = 80, nullable = false)
    private String name;

    @Column(name = "state_code", length = 2, nullable = false)
    private String stateCode;

    @Column(name = "warehouse_licence", length = 24)
    private String warehouseLicence;

    @Column(name = "licensed_capacity")
    private Integer licensedCapacity;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    public Location() {}

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

    public String getStateCode() {
        return stateCode;
    }

    public void setStateCode(String stateCode) {
        this.stateCode = stateCode;
    }

    public Optional<String> getWarehouseLicence() {
        return Optional.ofNullable(warehouseLicence);
    }

    /** Null leaves {@code warehouse_licence} empty. */
    public void setWarehouseLicence(String warehouseLicence) {
        this.warehouseLicence = warehouseLicence;
    }

    public Optional<Integer> getLicensedCapacity() {
        return Optional.ofNullable(licensedCapacity);
    }

    /** Null leaves {@code licensed_capacity} empty. */
    public void setLicensedCapacity(Integer licensedCapacity) {
        this.licensedCapacity = licensedCapacity;
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
