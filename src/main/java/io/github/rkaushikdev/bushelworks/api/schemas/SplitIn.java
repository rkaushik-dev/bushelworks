package io.github.rkaushikdev.bushelworks.api.schemas;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.bushelworks.domain.tickets.SplitRole;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * One party's share of a load, as a percentage of the whole. Sent with a ticket, and read back with
 * it.
 *
 * @param partyCode the party, one to 24 characters
 * @param percent the share
 * @param role why the party has it; an owner's share when the caller does not say
 */
public record SplitIn(String partyCode, BigDecimal percent, SplitRole role) {

    public SplitIn {
        Objects.requireNonNull(partyCode, "partyCode");
        Objects.requireNonNull(percent, "percent");
        Objects.requireNonNull(role, "role");
    }

    /** A share as a caller sends it. */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static SplitIn from(JsonNode json) {
        return Body.read(json, SplitIn::read);
    }

    /** A share read out of a body. */
    public static SplitIn read(Body in) {
        String partyCode = in.text("party_code", 1, 24);
        BigDecimal percent = in.quantity("percent");
        SplitRole role = in.choice("role", SplitRole.class, SplitRole.OWNER);
        return in.build(() -> new SplitIn(partyCode, percent, role));
    }
}
