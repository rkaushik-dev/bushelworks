package io.github.rkaushikdev.bushelworks.domain.liens;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One filed security interest against a producer.
 *
 * <p>A bank with a security interest in the crop tells the house so in writing, and from then on
 * the house is on the hook for the money if it pays the producer alone. An assignment is capped at
 * what it is written for. A joint payee is not capped at anything, because it is not an amount.
 *
 * <p>A filing does not stand for ever. A financing statement lapses five years after it was filed
 * unless the holder files a continuation, and a continuation only counts if it is filed in the six
 * months before the lapse; each one that counts carries the filing another five years from the day
 * it would have lapsed. A bank that forgot to continue has no claim on the cheque, and the house
 * that keeps putting its name on one is holding up a producer's money for nothing.
 *
 * @param reference the filing's reference
 * @param partyCode the producer
 * @param holder who holds the interest
 * @param kind the arrangement
 * @param filedOn when it was filed
 * @param commodityCode the commodity it covers, or nothing for a blanket interest
 * @param amountLimit what an assignment is written for
 * @param releasedOn when it was released, if it has been
 * @param continuedOn the continuation statements filed, oldest first
 */
public record Interest(
        String reference,
        String partyCode,
        String holder,
        LienKind kind,
        LocalDate filedOn,
        Optional<String> commodityCode,
        Optional<BigDecimal> amountLimit,
        Optional<LocalDate> releasedOn,
        List<LocalDate> continuedOn) {

    /** How long a filing, or a continuation of it, is effective for. */
    public static final Period EFFECTIVE_FOR = Period.ofYears(5);

    /** How long before the lapse a continuation may be filed and still count. */
    public static final Period CONTINUATION_WINDOW = Period.ofMonths(6);

    public Interest {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(partyCode, "partyCode");
        Objects.requireNonNull(holder, "holder");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(filedOn, "filedOn");
        Objects.requireNonNull(commodityCode, "commodityCode");
        Objects.requireNonNull(amountLimit, "amountLimit");
        Objects.requireNonNull(releasedOn, "releasedOn");
        continuedOn = continuedOn.stream().sorted().toList();
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
        LocalDate lapse = filedOn.plus(EFFECTIVE_FOR);
        for (LocalDate continuation : continuedOn) {
            if (continuation.isBefore(lapse.minus(CONTINUATION_WINDOW)) || !continuation.isBefore(lapse)) {
                throw new LienError(reference + ": a continuation on " + continuation
                        + " is outside the six months before the " + lapse + " lapse");
            }
            lapse = lapse.plus(EFFECTIVE_FOR);
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
                .releasedOn(releasedOn.orElse(null))
                .continuedOn(continuedOn);
    }

    /** The day the filing stops being effective, every timely continuation counted. */
    public LocalDate lapsesOn() {
        return filedOn.plus(EFFECTIVE_FOR.multipliedBy(continuedOn.size() + 1));
    }

    /** The first day a continuation of the filing as it stands may be filed. */
    public LocalDate continuableFrom() {
        return lapsesOn().minus(CONTINUATION_WINDOW);
    }

    /** The interest with one more continuation on file, refused unless it is timely. */
    public Interest continueOn(LocalDate day) {
        List<LocalDate> more = new ArrayList<>(continuedOn);
        more.add(day);
        return toBuilder().continuedOn(more).build();
    }

    /** Whether the interest bites on a day: from its filing until it is released or it lapses. */
    public boolean isLiveOn(LocalDate day) {
        boolean released = releasedOn.filter(on -> !day.isBefore(on)).isPresent();
        return !day.isBefore(filedOn) && day.isBefore(lapsesOn()) && !released;
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
        private List<LocalDate> continuedOn = List.of();

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

        /** The continuation statements filed, in any order. */
        public Builder continuedOn(List<LocalDate> value) {
            this.continuedOn = List.copyOf(value);
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
                    Optional.ofNullable(releasedOn),
                    continuedOn);
        }
    }
}
