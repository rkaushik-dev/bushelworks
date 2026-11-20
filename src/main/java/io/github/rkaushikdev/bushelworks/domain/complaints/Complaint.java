package io.github.rkaushikdev.bushelworks.domain.complaints;

import io.github.rkaushikdev.bushelworks.ConflictError;
import io.github.rkaushikdev.bushelworks.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * One thing somebody rang up about.
 *
 * <p>A buyer refuses a car, a producer says his corn was better than the ticket says, a feedlot
 * rings about a load that smelled. All three are complaints and all three have to be answered from
 * records rather than memory.
 *
 * @param reference the complaint's reference
 * @param source who rang
 * @param raisedOn when they rang
 * @param deliveredOn when the grain went
 * @param subject what they said
 * @param partyCode who it is, when known
 * @param binId the bin the grain came out of, when known
 * @param ticketNumber the scale ticket, when there is one
 * @param saleNumber the sale, when there is one
 * @param probeId the retained sample, when there is one
 * @param outcome how it ended, or open
 * @param credit what the house gave back
 * @param closedOn when it was closed
 * @param note what the office wrote on it
 */
public record Complaint(
        String reference,
        Source source,
        LocalDate raisedOn,
        LocalDate deliveredOn,
        String subject,
        Optional<String> partyCode,
        Optional<String> binId,
        Optional<String> ticketNumber,
        Optional<String> saleNumber,
        Optional<String> probeId,
        Outcome outcome,
        BigDecimal credit,
        Optional<LocalDate> closedOn,
        String note) {

    /** How long after a delivery a complaint is normally entertained, in days. */
    public static final int NORMAL_WINDOW_DAYS = 30;

    /** How long the retained half of a sample is kept, in days, unless the house says otherwise. */
    public static final int RETENTION_DAYS = 45;

    public Complaint {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(raisedOn, "raisedOn");
        Objects.requireNonNull(deliveredOn, "deliveredOn");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(partyCode, "partyCode");
        Objects.requireNonNull(binId, "binId");
        Objects.requireNonNull(ticketNumber, "ticketNumber");
        Objects.requireNonNull(saleNumber, "saleNumber");
        Objects.requireNonNull(probeId, "probeId");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(closedOn, "closedOn");
        Objects.requireNonNull(note, "note");
        if (reference.isEmpty()) {
            throw new ComplaintError("a complaint needs a reference");
        }
        if (subject.isEmpty()) {
            throw new ComplaintError(reference + ": a complaint needs a subject");
        }
        if (raisedOn.isBefore(deliveredOn)) {
            throw new ComplaintError(reference + ": raised before the grain was delivered");
        }
        if (credit.signum() < 0) {
            throw new ComplaintError(reference + ": a credit cannot be negative");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .reference(reference)
                .source(source)
                .raisedOn(raisedOn)
                .deliveredOn(deliveredOn)
                .subject(subject)
                .partyCode(partyCode.orElse(null))
                .binId(binId.orElse(null))
                .ticketNumber(ticketNumber.orElse(null))
                .saleNumber(saleNumber.orElse(null))
                .probeId(probeId.orElse(null))
                .outcome(outcome)
                .credit(credit)
                .closedOn(closedOn.orElse(null))
                .note(note);
    }

    /** How long the grain had been gone when the complaint arrived. */
    public int daysAfter() {
        return (int) ChronoUnit.DAYS.between(deliveredOn, raisedOn);
    }

    /** Raised outside the window the house normally entertains. */
    public boolean isLate() {
        return raisedOn.isAfter(deliveredOn.plusDays(NORMAL_WINDOW_DAYS));
    }

    public boolean isOpen() {
        return outcome == Outcome.OPEN;
    }

    /** Whether the retained half was still on the shelf when it arrived, at the usual retention. */
    public boolean sampleIsAvailable() {
        return sampleIsAvailable(RETENTION_DAYS);
    }

    /**
     * Whether the retained half was still on the shelf when it arrived.
     *
     * <p>Without it the house is arguing from a number it wrote down itself, which is not a position
     * it wins from.
     */
    public boolean sampleIsAvailable(int retentionDays) {
        return probeId.isPresent() && daysAfter() <= retentionDays;
    }

    public Complaint close(Outcome how, LocalDate on) {
        return close(how, on, Money.ZERO_AMOUNT, "");
    }

    public Complaint close(Outcome how, LocalDate on, BigDecimal given) {
        return close(how, on, given, "");
    }

    /** Close the complaint with an outcome, the credit given and a note. */
    public Complaint close(Outcome how, LocalDate on, BigDecimal given, String closingNote) {
        if (!isOpen()) {
            throw new ConflictError(reference + " was closed on "
                    + closedOn.map(LocalDate::toString).orElse("None") + " as " + outcome.value());
        }
        if (how == Outcome.OPEN) {
            throw new ComplaintError(reference + ": closing needs an outcome");
        }
        if (on.isBefore(raisedOn)) {
            throw new ComplaintError(reference + ": closed before it was raised");
        }
        if (how == Outcome.REJECTED && given.signum() > 0) {
            throw new ComplaintError(reference + ": a rejected complaint does not carry a credit");
        }
        return toBuilder()
                .outcome(how)
                .closedOn(on)
                .credit(Money.amount(given))
                .note(closingNote.isEmpty() ? note : closingNote)
                .build();
    }

    /** A complaint assembled a field at a time; open, no credit, no note unless told. */
    public static final class Builder {

        private String reference;
        private Source source;
        private LocalDate raisedOn;
        private LocalDate deliveredOn;
        private String subject;
        private String partyCode;
        private String binId;
        private String ticketNumber;
        private String saleNumber;
        private String probeId;
        private Outcome outcome = Outcome.OPEN;
        private BigDecimal credit = Money.ZERO_AMOUNT;
        private LocalDate closedOn;
        private String note = "";

        private Builder() {}

        public Builder reference(String value) {
            this.reference = value;
            return this;
        }

        public Builder source(Source value) {
            this.source = value;
            return this;
        }

        public Builder raisedOn(LocalDate value) {
            this.raisedOn = value;
            return this;
        }

        public Builder deliveredOn(LocalDate value) {
            this.deliveredOn = value;
            return this;
        }

        public Builder subject(String value) {
            this.subject = value;
            return this;
        }

        /** The party, or null when nobody is named. */
        public Builder partyCode(String value) {
            this.partyCode = value;
            return this;
        }

        /** The bin, or null when none is known. */
        public Builder binId(String value) {
            this.binId = value;
            return this;
        }

        /** The ticket, or null for none. */
        public Builder ticketNumber(String value) {
            this.ticketNumber = value;
            return this;
        }

        /** The sale, or null for none. */
        public Builder saleNumber(String value) {
            this.saleNumber = value;
            return this;
        }

        /** The retained sample, or null for none. */
        public Builder probeId(String value) {
            this.probeId = value;
            return this;
        }

        public Builder outcome(Outcome value) {
            this.outcome = value;
            return this;
        }

        public Builder credit(BigDecimal value) {
            this.credit = value;
            return this;
        }

        /** The day it was closed, or null while open. */
        public Builder closedOn(LocalDate value) {
            this.closedOn = value;
            return this;
        }

        public Builder note(String value) {
            this.note = value;
            return this;
        }

        public Complaint build() {
            return new Complaint(
                    reference,
                    source,
                    raisedOn,
                    deliveredOn,
                    subject,
                    Optional.ofNullable(partyCode),
                    Optional.ofNullable(binId),
                    Optional.ofNullable(ticketNumber),
                    Optional.ofNullable(saleNumber),
                    Optional.ofNullable(probeId),
                    outcome,
                    credit,
                    Optional.ofNullable(closedOn),
                    note);
        }
    }
}
