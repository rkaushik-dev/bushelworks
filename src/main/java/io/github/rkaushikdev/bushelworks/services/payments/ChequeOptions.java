package io.github.rkaushikdev.bushelworks.services.payments;

import io.github.rkaushikdev.bushelworks.services.audit.Audit;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything a cheque run takes beyond the day and the first cheque number.
 *
 * <pre>{@code
 * Payments.runCheques(uow, paidOn, "010441",
 *         ChequeOptions.defaults().withPartyCodes(List.of("MERRICK")).withActor("bookkeeper"));
 * }</pre>
 *
 * @param partyCodes only these producers, when given; every producer with something unpaid otherwise
 * @param minimum the smallest balance worth a cheque; a producer owed less is held
 * @param actor who ran the cheques, for the audit trail
 */
public record ChequeOptions(Optional<List<String>> partyCodes, BigDecimal minimum, String actor) {

    /** A cent: anything owed at all gets a cheque. */
    public static final BigDecimal ONE_CENT = new BigDecimal("0.01");

    public ChequeOptions {
        Objects.requireNonNull(minimum, "minimum");
        Objects.requireNonNull(actor, "actor");
        partyCodes = partyCodes.map(List::copyOf);
    }

    /** Every producer, a cheque for anything from a cent, by the system. */
    public static ChequeOptions defaults() {
        return new ChequeOptions(Optional.empty(), ONE_CENT, Audit.SYSTEM);
    }

    /** Null means every producer with something unpaid. */
    public ChequeOptions withPartyCodes(List<String> value) {
        return new ChequeOptions(Optional.ofNullable(value), minimum, actor);
    }

    public ChequeOptions withMinimum(BigDecimal value) {
        return new ChequeOptions(partyCodes, value, actor);
    }

    public ChequeOptions withActor(String value) {
        return new ChequeOptions(partyCodes, minimum, value);
    }
}
