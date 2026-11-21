package io.github.rkaushikdev.bushelworks.persistence.models;

import io.github.rkaushikdev.bushelworks.persistence.Columns;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.hibernate.annotations.Check;

/**
 * One thing that happened, as it looked when it happened.
 *
 * <p>Not a log. The log is for the office and rotates; this is evidence and does not. Every row
 * records who did what to which document and what the money was, and nothing in the application
 * updates or deletes one.
 *
 * <p>The rows are deliberately denormalised. A foreign key to a settlement that is later voided and
 * reissued leaves the audit row pointing at a document that no longer says what it said, so the
 * document number is stored as text alongside the figures as they stood.
 */
@Entity
@Table(
        name = "audit_entry",
        indexes = {
            @Index(name = "ix_audit_entry_recorded_at", columnList = "recorded_at"),
            @Index(name = "ix_audit_entry_document", columnList = "document_kind, document_number"),
            @Index(name = "ix_audit_entry_actor", columnList = "actor")
        })
@Check(name = "ck_audit_entry_action_not_blank", constraints = "length(action) > 0")
public class AuditRow extends Row {

    /** When it happened, in UTC; now, unless set before the row is written. */
    @Column(name = "recorded_at", nullable = false)
    private OffsetDateTime recordedAt;

    @Column(name = "effective_on")
    private LocalDate effectiveOn;

    @Column(name = "actor", length = 60, nullable = false)
    private String actor = "system";

    @Column(name = "action", length = 40, nullable = false)
    private String action;

    @Column(name = "document_kind", length = 24, nullable = false)
    private String documentKind;

    @Column(name = "document_number", length = 24, nullable = false)
    private String documentNumber;

    @Column(name = "party_code", length = 24)
    private String partyCode;

    @Column(name = "location_code", length = 12)
    private String locationCode;

    @Column(name = "amount", precision = Columns.MONEY_PRECISION, scale = Columns.MONEY_SCALE)
    private BigDecimal amount;

    @Column(name = "detail", length = 400)
    private String detail;

    @Column(name = "request_id", length = 32)
    private String requestId;

    public AuditRow() {}

    public OffsetDateTime getRecordedAt() {
        return recordedAt;
    }

    public void setRecordedAt(OffsetDateTime recordedAt) {
        this.recordedAt = recordedAt;
    }

    public Optional<LocalDate> getEffectiveOn() {
        return Optional.ofNullable(effectiveOn);
    }

    /** Null leaves {@code effective_on} empty. */
    public void setEffectiveOn(LocalDate effectiveOn) {
        this.effectiveOn = effectiveOn;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getDocumentKind() {
        return documentKind;
    }

    public void setDocumentKind(String documentKind) {
        this.documentKind = documentKind;
    }

    public String getDocumentNumber() {
        return documentNumber;
    }

    public void setDocumentNumber(String documentNumber) {
        this.documentNumber = documentNumber;
    }

    public Optional<String> getPartyCode() {
        return Optional.ofNullable(partyCode);
    }

    /** Null leaves {@code party_code} empty. */
    public void setPartyCode(String partyCode) {
        this.partyCode = partyCode;
    }

    public Optional<String> getLocationCode() {
        return Optional.ofNullable(locationCode);
    }

    /** Null leaves {@code location_code} empty. */
    public void setLocationCode(String locationCode) {
        this.locationCode = locationCode;
    }

    public Optional<BigDecimal> getAmount() {
        return Optional.ofNullable(amount);
    }

    /** Null leaves {@code amount} empty. */
    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public Optional<String> getDetail() {
        return Optional.ofNullable(detail);
    }

    /** Null leaves {@code detail} empty. */
    public void setDetail(String detail) {
        this.detail = detail;
    }

    public Optional<String> getRequestId() {
        return Optional.ofNullable(requestId);
    }

    /** Null leaves {@code request_id} empty. */
    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    @PrePersist
    void stampRecorded() {
        if (recordedAt == null) {
            recordedAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    @Override
    public String toString() {
        return action + " " + documentKind + " " + documentNumber;
    }
}
