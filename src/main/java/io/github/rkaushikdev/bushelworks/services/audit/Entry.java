package io.github.rkaushikdev.bushelworks.services.audit;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * One audit entry, read back.
 *
 * @param recordedAt when it was written, in UTC
 * @param actor who did it
 * @param action what was done, one of {@link Action}
 * @param documentKind the kind of document it was done to, usually one of {@link Document}
 * @param documentNumber the document's number
 * @param partyCode the producer it concerned, when there was one
 * @param amount the money, as it stood, when there was any
 * @param detail what else was said
 */
public record Entry(
        OffsetDateTime recordedAt,
        String actor,
        String action,
        String documentKind,
        String documentNumber,
        Optional<String> partyCode,
        Optional<BigDecimal> amount,
        Optional<String> detail) {

    public Entry {
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(documentKind, "documentKind");
        Objects.requireNonNull(documentNumber, "documentNumber");
        Objects.requireNonNull(partyCode, "partyCode");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(detail, "detail");
    }
}
