package io.github.rkaushikdev.bushelworks.persistence.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.util.Optional;
import org.hibernate.annotations.Check;

/**
 * A producer, a landlord, a lienholder or a buyer.
 *
 * <p>One table rather than four. A landlord on one ticket is a producer on the next, and splitting
 * them means keeping two addresses for the same person.
 */
@Entity
@Table(
        name = "party",
        uniqueConstraints = @UniqueConstraint(name = "uq_party_code", columnNames = "code"),
        indexes = {@Index(name = "ix_party_sort_name", columnList = "sort_name")})
@Check(name = "ck_party_code_not_blank", constraints = "length(code) > 0")
public class Party extends Timestamped {

    @Column(name = "code", length = 24, nullable = false)
    private String code;

    @Column(name = "name", length = 120, nullable = false)
    private String name;

    @Column(name = "sort_name", length = 120, nullable = false)
    private String sortName;

    @Column(name = "tax_id", length = 24)
    private String taxId;

    @Column(name = "state_code", length = 2, nullable = false)
    private String stateCode = "KS";

    @Column(name = "address_line1", length = 80)
    private String addressLine1;

    @Column(name = "address_line2", length = 80)
    private String addressLine2;

    @Column(name = "city", length = 60)
    private String city;

    @Column(name = "postal_code", length = 12)
    private String postalCode;

    @Column(name = "phone", length = 24)
    private String phone;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "checkoff_exempt", nullable = false)
    private boolean checkoffExempt = false;

    @Column(name = "opened_on")
    private LocalDate openedOn;

    @Column(name = "closed_on")
    private LocalDate closedOn;

    public Party() {}

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

    public String getSortName() {
        return sortName;
    }

    public void setSortName(String sortName) {
        this.sortName = sortName;
    }

    public Optional<String> getTaxId() {
        return Optional.ofNullable(taxId);
    }

    /** Null leaves {@code tax_id} empty. */
    public void setTaxId(String taxId) {
        this.taxId = taxId;
    }

    public String getStateCode() {
        return stateCode;
    }

    public void setStateCode(String stateCode) {
        this.stateCode = stateCode;
    }

    public Optional<String> getAddressLine1() {
        return Optional.ofNullable(addressLine1);
    }

    /** Null leaves {@code address_line1} empty. */
    public void setAddressLine1(String addressLine1) {
        this.addressLine1 = addressLine1;
    }

    public Optional<String> getAddressLine2() {
        return Optional.ofNullable(addressLine2);
    }

    /** Null leaves {@code address_line2} empty. */
    public void setAddressLine2(String addressLine2) {
        this.addressLine2 = addressLine2;
    }

    public Optional<String> getCity() {
        return Optional.ofNullable(city);
    }

    /** Null leaves {@code city} empty. */
    public void setCity(String city) {
        this.city = city;
    }

    public Optional<String> getPostalCode() {
        return Optional.ofNullable(postalCode);
    }

    /** Null leaves {@code postal_code} empty. */
    public void setPostalCode(String postalCode) {
        this.postalCode = postalCode;
    }

    public Optional<String> getPhone() {
        return Optional.ofNullable(phone);
    }

    /** Null leaves {@code phone} empty. */
    public void setPhone(String phone) {
        this.phone = phone;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isCheckoffExempt() {
        return checkoffExempt;
    }

    public void setCheckoffExempt(boolean checkoffExempt) {
        this.checkoffExempt = checkoffExempt;
    }

    public Optional<LocalDate> getOpenedOn() {
        return Optional.ofNullable(openedOn);
    }

    /** Null leaves {@code opened_on} empty. */
    public void setOpenedOn(LocalDate openedOn) {
        this.openedOn = openedOn;
    }

    public Optional<LocalDate> getClosedOn() {
        return Optional.ofNullable(closedOn);
    }

    /** Null leaves {@code closed_on} empty. */
    public void setClosedOn(LocalDate closedOn) {
        this.closedOn = closedOn;
    }

    @Override
    public String toString() {
        return code + " " + name;
    }
}
