package io.github.rkaushikdev.bushelworks.services.deferred;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * What pricing part of a deferred lot comes to, as the two lines the settlement prints.
 *
 * @param gross the bushels priced at the agreed price
 * @param fee the service fee on those bushels for the time they were carried
 */
public record PricedLot(BigDecimal gross, BigDecimal fee) {

    public PricedLot {
        Objects.requireNonNull(gross, "gross");
        Objects.requireNonNull(fee, "fee");
    }
}
