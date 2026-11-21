package io.github.rkaushikdev.bushelworks.services.audit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything an audit entry says beyond what was done to which document: who did it, for whom,
 * where, for how much, on which business day, and anything else worth a sentence.
 *
 * <pre>{@code
 * Audit.record(uow, Action.RECORDED, Document.TICKET, "A-10441",
 *         Particulars.by("rmerrick").withPartyCode("MERRICK").withLocationCode("HOME"));
 * }</pre>
 *
 * @param actor who did it; empty means {@link Audit#SYSTEM}
 * @param partyCode the producer it concerns
 * @param locationCode the site it happened at
 * @param amount the money, as it stood
 * @param detail anything else; more than {@link Audit#DETAIL_LENGTH} characters is trimmed
 * @param effectiveOn the business day it belongs to, which is not always the day it was keyed
 */
public record Particulars(
        String actor,
        Optional<String> partyCode,
        Optional<String> locationCode,
        Optional<BigDecimal> amount,
        Optional<String> detail,
        Optional<LocalDate> effectiveOn) {

    public Particulars {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(partyCode, "partyCode");
        Objects.requireNonNull(locationCode, "locationCode");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(effectiveOn, "effectiveOn");
    }

    /** Nothing beyond the action and the document, done by the system. */
    public static Particulars none() {
        return by(Audit.SYSTEM);
    }

    /** Done by {@code actor}, with nothing else said. Null or empty means the system. */
    public static Particulars by(String actor) {
        return new Particulars(
                actor == null ? "" : actor,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    /** Null or empty means the system. */
    public Particulars withActor(String value) {
        return new Particulars(value == null ? "" : value, partyCode, locationCode, amount, detail, effectiveOn);
    }

    /** Null clears it. */
    public Particulars withPartyCode(String value) {
        return new Particulars(actor, Optional.ofNullable(value), locationCode, amount, detail, effectiveOn);
    }

    /** Null clears it. */
    public Particulars withLocationCode(String value) {
        return new Particulars(actor, partyCode, Optional.ofNullable(value), amount, detail, effectiveOn);
    }

    /** Null clears it. */
    public Particulars withAmount(BigDecimal value) {
        return new Particulars(actor, partyCode, locationCode, Optional.ofNullable(value), detail, effectiveOn);
    }

    /** Null clears it. */
    public Particulars withDetail(String value) {
        return new Particulars(actor, partyCode, locationCode, amount, Optional.ofNullable(value), effectiveOn);
    }

    /** Null clears it. */
    public Particulars withEffectiveOn(LocalDate value) {
        return new Particulars(actor, partyCode, locationCode, amount, detail, Optional.ofNullable(value));
    }
}
