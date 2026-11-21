package io.github.rkaushikdev.bushelworks.domain.liens;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * One filed security interest against a producer.
 *
 * <p>A bank with a security interest in the crop tells the house so in writing, and from then on
 * the house is on the hook for the money if it pays the producer alone. An assignment is capped at
 * what it is written for. A joint payee is not capped at anything, because it is not an amount.
 *
 * @param reference the filing's reference
 * @param partyCode the producer
 * @param holder who holds the interest
 * @param kind the arrangement
 * @param filedOn when it was filed
 * @param commodityCode the commodity it covers, or nothing for a blanket interest
 * @param amountLimit what an assignment is written for
 * @param releasedOn when it was released, if it has been
 */
public record Interest(
        String reference,
        String partyCode,
        String holder,
        LienKind kind,
        LocalDate filedOn,
        Optional<String> commodityCode,
        Optional<BigDecimal> amountLimit,
        Optional<LocalDate> releasedOn) {

    public Interest {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(partyCode, "partyCode");
        Objects.requireNonNull(holder, "holder");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(filedOn, "filedOn");
        Objects.requireNonNull(commodityCode, "commodityCode");
        Objects.requireNonNull(amountLimit, "amountLimit");
        Objects.requireNonNull(releasedOn, "releasedOn");
        if (reference.isEmpty()) {
            throw new LienError("an interest needs a reference");
        }
        if (holder.isEmpty()) {
            throw new LienError(reference + ": an interest needs a holder");
        }
        if (kind == LienKind.ASSIGNMENT && amountLimit.isEmpty()) {
            throw new LienError(reference + ": an assignment needs an amount");
        }
        if (amountLimit.isPresent() && amountLimit.get().signum() <= 0) {
            throw new LienError(reference + ": an assignment has to be positive");
        }
        if (kind == LienKind.JOINT_PAYEE && amountLimit.isPresent()) {
            throw new LienError(reference + ": a joint payee is not an amount and cannot carry a limit");
        }
        if (releasedOn.isPresent() && releasedOn.get().isBefore(filedOn)) {
            throw new LienError(reference + ": released before it was filed");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .reference(reference)
                .partyCode(partyCode)
                .holder(holder)
                .kind(kind)
                .filedOn(filedOn)
                .commodityCode(commodityCode.orElse(null))
                .amountLimit(amountLimit.orElse(null))
                .releasedOn(releasedOn.orElse(null));
    }

    /** Whether the interest bites on a day: from its filing until the day it is released. */
    public boolean isLiveOn(LocalDate day) {
        if (day.isBefore(filedOn)) {
            return false;
        }
        return releasedOn.isEmpty() || day.isBefore(releasedOn.get());
    }

    /** A blanket interest covers everything; a specific one does not. */
    public boolean covers(String commodity) {
        return commodityCode.isEmpty() || commodityCode.get().equals(commodity);
    }

    public boolean appliesTo(String commodity, LocalDate day) {
        return isLiveOn(day) && covers(commodity);
    }

    /** An interest assembled a field at a time. */
    public static final class Builder {

        private String reference;
        private String partyCode;
        private String holder;
        private LienKind kind;
        private LocalDate filedOn;
        private String commodityCode;
        private BigDecimal amountLimit;
        private LocalDate releasedOn;

        private Builder() {}

        public Builder reference(String value) {
            this.reference = value;
            return this;
        }

        public Builder partyCode(String value) {
            this.partyCode = value;
            return this;
        }

        public Builder holder(String value) {
            this.holder = value;
            return this;
        }

        public Builder kind(LienKind value) {
            this.kind = value;
            return this;
        }

        public Builder filedOn(LocalDate value) {
            this.filedOn = value;
            return this;
        }

        /** The commodity covered, or null for a blanket interest. */
        public Builder commodityCode(String value) {
            this.commodityCode = value;
            return this;
        }

        /** What an assignment is written for, or null. */
        public Builder amountLimit(BigDecimal value) {
            this.amountLimit = value;
            return this;
        }

        /** When it was released, or null while it stands. */
        public Builder releasedOn(LocalDate value) {
            this.releasedOn = value;
            return this;
        }

        public Interest build() {
            return new Interest(
                    reference,
                    partyCode,
                    holder,
                    kind,
                    filedOn,
                    Optional.ofNullable(commodityCode),
                    Optional.ofNullable(amountLimit),
                    Optional.ofNullable(releasedOn));
        }
    }
}
