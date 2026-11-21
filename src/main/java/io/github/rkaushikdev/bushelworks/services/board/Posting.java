package io.github.rkaushikdev.bushelworks.services.board;

import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.domain.pricing.CashBid;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * One bid as it went up, with when and by whom.
 *
 * @param slot what the bid is for
 * @param bid the futures and basis it was built from
 * @param postedAt when it went up
 * @param postedBy who put it up
 */
public record Posting(Slot slot, CashBid bid, OffsetDateTime postedAt, String postedBy) {

    public Posting {
        Objects.requireNonNull(slot, "slot");
        Objects.requireNonNull(bid, "bid");
        Objects.requireNonNull(postedAt, "postedAt");
        Objects.requireNonNull(postedBy, "postedBy");
    }

    /** The cash price the board shows. */
    public BigDecimal cash() {
        return bid.cash();
    }

    public BigDecimal basis() {
        return Money.price(bid.basis());
    }
}
