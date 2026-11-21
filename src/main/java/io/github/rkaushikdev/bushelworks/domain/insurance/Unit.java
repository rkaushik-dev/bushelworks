package io.github.rkaushikdev.bushelworks.domain.insurance;

import io.github.rkaushikdev.bushelworks.Units;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;

/**
 * One insurable unit: a piece of ground with an insurable history.
 *
 * <p>The adjuster wants production by unit, not by producer, and one producer usually farms several.
 * The link from a ticket to a unit is the farm code on the ticket, which is why the scale house is
 * told to key it even when nobody asks.
 *
 * @param unitNumber the unit
 * @param partyCode the producer
 * @param commodityCode the crop
 * @param cropYear the crop year
 * @param acres planted acres
 * @param approvedYield bushels an acre the policy guarantees
 * @param farmCodes the farm codes on tickets that belong to this unit
 */
public record Unit(
        String unitNumber,
        String partyCode,
        String commodityCode,
        int cropYear,
        BigDecimal acres,
        BigDecimal approvedYield,
        Set<String> farmCodes) {

    public Unit {
        Objects.requireNonNull(unitNumber, "unitNumber");
        Objects.requireNonNull(partyCode, "partyCode");
        Objects.requireNonNull(commodityCode, "commodityCode");
        farmCodes = Set.copyOf(farmCodes);
        if (unitNumber.isEmpty()) {
            throw new InsuranceError("a unit needs a number");
        }
        if (acres.signum() <= 0) {
            throw new InsuranceError(unitNumber + ": a unit needs acres");
        }
        if (approvedYield.signum() <= 0) {
            throw new InsuranceError(unitNumber + ": a unit needs an approved yield");
        }
    }

    /** Acres times the approved yield, which is what a claim is measured against. */
    public BigDecimal guaranteedBushels() {
        return Units.quantize(acres.multiply(approvedYield), Units.BUSHEL_PLACES);
    }

    public boolean covers(String farmCode) {
        return farmCodes.contains(farmCode);
    }
}
