package io.github.rkaushikdev.bushelworks.domain.commodity;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The commodity master: the commodities the house buys, by code.
 *
 * <p>These are US standards. A house that buys canola or field peas adds a row; it does not change
 * the shape of the row.
 */
public final class Commodities {

    private static final Map<String, Commodity> MASTER = new ConcurrentHashMap<>();

    public static final Commodity CORN = register(new Commodity(
            "CORN",
            "Yellow Corn",
            new BigDecimal("56"),
            new BigDecimal("15.0"),
            PriceUnit.BUSHEL,
            Set.of(Factor.TEST_WEIGHT, Factor.MOISTURE, Factor.BCFM, Factor.DAMAGE_TOTAL),
            optional(Factor.DAMAGE_HEAT)));

    public static final Commodity SOYBEANS = register(new Commodity(
            "SOYB",
            "Yellow Soybeans",
            new BigDecimal("60"),
            new BigDecimal("13.0"),
            PriceUnit.BUSHEL,
            Set.of(Factor.TEST_WEIGHT, Factor.MOISTURE, Factor.FOREIGN_MATERIAL, Factor.DAMAGE_TOTAL, Factor.SPLITS),
            optional(Factor.DAMAGE_HEAT, Factor.OIL)));

    public static final Commodity HRW_WHEAT = register(new Commodity(
            "HRW",
            "Hard Red Winter Wheat",
            new BigDecimal("60"),
            new BigDecimal("13.5"),
            PriceUnit.BUSHEL,
            Set.of(
                    Factor.TEST_WEIGHT,
                    Factor.MOISTURE,
                    Factor.DOCKAGE,
                    Factor.DAMAGE_TOTAL,
                    Factor.SHRUNKEN_BROKEN,
                    Factor.TOTAL_DEFECTS),
            optional(Factor.PROTEIN, Factor.FALLING_NUMBER, Factor.DAMAGE_HEAT)));

    public static final Commodity GRAIN_SORGHUM = register(new Commodity(
            "MILO",
            "Grain Sorghum",
            new BigDecimal("56"),
            new BigDecimal("14.0"),
            PriceUnit.BUSHEL,
            Set.of(Factor.TEST_WEIGHT, Factor.MOISTURE, Factor.FOREIGN_MATERIAL, Factor.DAMAGE_TOTAL),
            optional()));

    public static final Commodity SUNFLOWER = register(new Commodity(
            "SUNF",
            "Oil Sunflower Seed",
            new BigDecimal("25"),
            new BigDecimal("10.0"),
            PriceUnit.HUNDREDWEIGHT,
            Set.of(Factor.MOISTURE, Factor.FOREIGN_MATERIAL, Factor.DAMAGE_TOTAL),
            optional(Factor.OIL)));

    private Commodities() {}

    /**
     * A commodity's optional factors, with the counts and percentages that can make any load Sample
     * Grade, which a grader may always record.
     */
    private static Set<Factor> optional(Factor... factors) {
        Set<Factor> all = EnumSet.of(
                Factor.STONES,
                Factor.GLASS,
                Factor.CROTALARIA,
                Factor.CASTOR_BEANS,
                Factor.UNKNOWN_FOREIGN,
                Factor.ANIMAL_FILTH);
        all.addAll(Arrays.asList(factors));
        return all;
    }

    /** Put a commodity on the master, replacing one with the same code. */
    public static Commodity register(Commodity commodity) {
        MASTER.put(commodity.code(), commodity);
        return commodity;
    }

    /** The commodity with this code. */
    public static Commodity get(String code) {
        Commodity found = MASTER.get(code);
        if (found == null) {
            throw new UnknownCommodity(code);
        }
        return found;
    }

    /** Every code on the master, sorted. */
    public static List<String> codes() {
        return MASTER.keySet().stream().sorted().toList();
    }
}
