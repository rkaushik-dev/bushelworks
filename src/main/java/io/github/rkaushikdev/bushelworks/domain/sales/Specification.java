package io.github.rkaushikdev.bushelworks.domain.sales;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What the buyer will accept, and what lets him refuse.
 *
 * <p>A sale has a quality specification with a rejection clause, not a discount schedule. The buyer
 * can refuse a car, and a refused car is still the house's.
 *
 * @param maxMoisture the wettest grain accepted
 * @param minTestWeight the lightest grain accepted
 * @param maxDamage the most total damage accepted
 * @param maxForeignMaterial the most foreign material accepted
 * @param rejectOnOdour whether an odour lets the buyer refuse
 */
public record Specification(
        BigDecimal maxMoisture,
        BigDecimal minTestWeight,
        BigDecimal maxDamage,
        BigDecimal maxForeignMaterial,
        boolean rejectOnOdour) {

    public Specification {
        if (maxMoisture.signum() <= 0 || maxMoisture.compareTo(Decimals.HUNDRED) >= 0) {
            throw new SaleError("moisture specification out of range");
        }
        if (minTestWeight.signum() <= 0) {
            throw new SaleError("test weight specification out of range");
        }
        Objects.requireNonNull(maxDamage, "maxDamage");
        Objects.requireNonNull(maxForeignMaterial, "maxForeignMaterial");
    }

    /** A specification that also refuses on odour. */
    public Specification(
            BigDecimal maxMoisture, BigDecimal minTestWeight, BigDecimal maxDamage, BigDecimal maxForeignMaterial) {
        this(maxMoisture, minTestWeight, maxDamage, maxForeignMaterial, true);
    }

    /**
     * Which clauses this load breaks, named the way the contract names them.
     *
     * <p>A factor the load was not tested for cannot break a clause. Foreign material is read as
     * BCFM when the sample has no foreign material reading, or reads zero.
     */
    public List<String> failures(Map<Factor, BigDecimal> sample) {
        List<String> broken = new ArrayList<>();
        BigDecimal moisture = sample.get(Factor.MOISTURE);
        if (moisture != null && moisture.compareTo(maxMoisture) > 0) {
            broken.add("moisture");
        }
        BigDecimal testWeight = sample.get(Factor.TEST_WEIGHT);
        if (testWeight != null && testWeight.compareTo(minTestWeight) < 0) {
            broken.add("test weight");
        }
        BigDecimal damage = sample.get(Factor.DAMAGE_TOTAL);
        if (damage != null && damage.compareTo(maxDamage) > 0) {
            broken.add("damage");
        }
        BigDecimal foreignMaterial = sample.get(Factor.FOREIGN_MATERIAL);
        BigDecimal foreign =
                foreignMaterial != null && foreignMaterial.signum() != 0 ? foreignMaterial : sample.get(Factor.BCFM);
        if (foreign != null && foreign.compareTo(maxForeignMaterial) > 0) {
            broken.add("foreign material");
        }
        return List.copyOf(broken);
    }

    public boolean accepts(Map<Factor, BigDecimal> sample) {
        return failures(sample).isEmpty();
    }
}
