package io.github.rkaushikdev.bushelworks.services.parties;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.domain.tickets.SplitShare;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * One farm's split, as it stood on a date.
 *
 * @param farmCode the farm
 * @param effectiveFrom the first day the split applies
 * @param shares who takes what part of every load off the farm
 */
public record Agreement(String farmCode, LocalDate effectiveFrom, List<SplitShare> shares) {

    public Agreement {
        Objects.requireNonNull(farmCode, "farmCode");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        shares = List.copyOf(shares);
    }

    /** The shares added up. */
    public BigDecimal totalPercent() {
        BigDecimal total = BigDecimal.ZERO;
        for (SplitShare share : shares) {
            total = total.add(share.percent());
        }
        return total;
    }

    /** Whether the shares make exactly the whole load. */
    public boolean isBalanced() {
        return totalPercent().compareTo(Decimals.HUNDRED) == 0;
    }
}
