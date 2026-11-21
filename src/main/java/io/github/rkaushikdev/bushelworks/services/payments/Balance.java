package io.github.rkaushikdev.bushelworks.services.payments;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * What a producer's unpaid statements add up to.
 *
 * @param partyCode the producer
 * @param owed the net of every unpaid statement; zero or less is a debit balance
 */
public record Balance(String partyCode, BigDecimal owed) {

    public Balance {
        Objects.requireNonNull(partyCode, "partyCode");
        Objects.requireNonNull(owed, "owed");
    }
}
