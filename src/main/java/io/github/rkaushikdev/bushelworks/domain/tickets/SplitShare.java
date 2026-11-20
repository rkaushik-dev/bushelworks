package io.github.rkaushikdev.bushelworks.domain.tickets;

import io.github.rkaushikdev.bushelworks.Decimals;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * One party's share of a load, as a percentage of the whole.
 *
 * @param partyId the party
 * @param percent the share, above zero and at most a hundred
 * @param role why the party has the share
 */
public record SplitShare(String partyId, BigDecimal percent, SplitRole role) {

    public SplitShare {
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(role, "role");
        if (partyId.isEmpty()) {
            throw new TicketError("a split share needs a party");
        }
        if (percent.signum() <= 0) {
            throw new TicketError(partyId + ": a share must be more than zero");
        }
        if (percent.compareTo(Decimals.HUNDRED) > 0) {
            throw new TicketError(partyId + ": a share cannot exceed the whole load");
        }
    }

    /** An owner's share. */
    public SplitShare(String partyId, BigDecimal percent) {
        this(partyId, percent, SplitRole.OWNER);
    }
}
