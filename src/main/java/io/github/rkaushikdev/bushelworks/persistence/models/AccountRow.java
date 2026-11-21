package io.github.rkaushikdev.bushelworks.persistence.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Optional;
import org.hibernate.annotations.Check;

/**
 * One account in the filed chart.
 *
 * <p>The parent is a code, not a foreign key. The chart is filed once per house and read as a
 * whole, so the accounts can go in in any order.
 */
@Entity
@Table(
        name = "ledger_account",
        uniqueConstraints = @UniqueConstraint(name = "uq_ledger_account_code", columnNames = "code"),
        indexes = {@Index(name = "ix_ledger_account_parent_code", columnList = "parent_code")})
@Check(
        name = "ck_ledger_account_type_is_known",
        constraints = "type in ('asset', 'liability', 'equity', 'revenue', 'expense')")
@Check(name = "ck_ledger_account_not_its_own_parent", constraints = "code <> parent_code")
public class AccountRow extends Timestamped {

    @Column(name = "code", length = 12, nullable = false)
    private String code;

    @Column(name = "name", length = 80, nullable = false)
    private String name;

    @Column(name = "type", length = 12, nullable = false)
    private String type;

    @Column(name = "parent_code", length = 12)
    private String parentCode;

    @Column(name = "closes_to", length = 12)
    private String closesTo;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    public AccountRow() {}

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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Optional<String> getParentCode() {
        return Optional.ofNullable(parentCode);
    }

    /** Null leaves {@code parent_code} empty. */
    public void setParentCode(String parentCode) {
        this.parentCode = parentCode;
    }

    public Optional<String> getClosesTo() {
        return Optional.ofNullable(closesTo);
    }

    /** Null leaves {@code closes_to} empty. */
    public void setClosesTo(String closesTo) {
        this.closesTo = closesTo;
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
