package io.github.rkaushikdev.bushelworks.services.payments;

import io.github.rkaushikdev.bushelworks.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What one pass over the unpaid statements produced.
 *
 * @param paidOn the day the cheques were written
 * @param cheques one per producer paid, in producer order
 * @param held the producers whose balance was under the minimum, in producer order
 */
public record ChequeRun(LocalDate paidOn, List<Cheque> cheques, List<Balance> held) {

    public ChequeRun {
        Objects.requireNonNull(paidOn, "paidOn");
        cheques = List.copyOf(cheques);
        held = List.copyOf(held);
    }

    /** Everything the run wrote. */
    public BigDecimal total() {
        return Money.total(cheques.stream().map(Cheque::dollars).toList());
    }

    /** How many cheques the run wrote. */
    public int count() {
        return cheques.size();
    }

    /** The cheque written to one producer, if the run wrote one. */
    public Optional<Cheque> forParty(String partyCode) {
        return cheques.stream()
                .filter(cheque -> cheque.partyCode().equals(partyCode))
                .findFirst();
    }
}
