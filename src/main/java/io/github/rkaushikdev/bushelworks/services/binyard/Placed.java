package io.github.rkaushikdev.bushelworks.services.binyard;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Where a load went.
 *
 * @param binId the bin it went into
 * @param bushels how much went in
 * @param commodityCode what it was
 * @param headroomLeft what the bin will still take before it is called full
 */
public record Placed(String binId, BigDecimal bushels, String commodityCode, BigDecimal headroomLeft) {

    public Placed {
        Objects.requireNonNull(binId, "binId");
        Objects.requireNonNull(bushels, "bushels");
        Objects.requireNonNull(commodityCode, "commodityCode");
        Objects.requireNonNull(headroomLeft, "headroomLeft");
    }
}
