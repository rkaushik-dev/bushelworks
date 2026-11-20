package io.github.rkaushikdev.bushelworks.domain.blending;

import io.github.rkaushikdev.bushelworks.BushelworksError;
import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.Money;
import io.github.rkaushikdev.bushelworks.Units;
import io.github.rkaushikdev.bushelworks.domain.commodity.Commodity;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.domain.grade.GradeResult;
import io.github.rkaushikdev.bushelworks.domain.grade.NumericalGrade;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The blending desk.
 *
 * <p>A bin of No. 3 corn is worth less than a bin of No. 2, and if the house has something clean to
 * mix it with, the blend can be worth more than the parts. Which factors blend and which do not is
 * the whole question.
 *
 * <p>Factors that are a percentage of the sample blend by weight: damage, foreign material, splits.
 * Two thousand bushels at four percent damage mixed with two thousand at one percent gives four
 * thousand at two and a half.
 *
 * <p>Test weight does not blend by weight. It is a density and it blends closer to a harmonic mean,
 * and the practical rule the trade uses is that a blend tests slightly below the weighted average.
 * The desk applies the weighted average and then a penalty, which is conservative in the direction
 * that matters: the house discovers the blend is better than predicted rather than worse.
 */
public final class Blending {

    /**
     * Test weight comes in below the weighted average by about this much on a real blend. Measured
     * across eleven blends in the 2023 harvest.
     */
    public static final BigDecimal TEST_WEIGHT_PENALTY = new BigDecimal("0.3");

    /** The factors that are a percentage of the sample, and so blend by weight. */
    public static final Set<Factor> BLENDS_BY_WEIGHT = Set.copyOf(EnumSet.of(
            Factor.MOISTURE,
            Factor.DOCKAGE,
            Factor.FOREIGN_MATERIAL,
            Factor.BCFM,
            Factor.DAMAGE_TOTAL,
            Factor.DAMAGE_HEAT,
            Factor.SPLITS,
            Factor.SHRUNKEN_BROKEN,
            Factor.TOTAL_DEFECTS,
            Factor.PROTEIN,
            Factor.OIL));

    private static final BigDecimal SAMPLE_PLACES = new BigDecimal("0.01");
    private static final BigDecimal DEFAULT_STEP = new BigDecimal("100");

    private Blending() {}

    /** Mix lots and work out what the mixture tests. */
    public static Blend blend(List<Lot> lots) {
        if (lots.isEmpty()) {
            throw new BlendError("nothing to blend");
        }
        if (lots.size() == 1) {
            Lot only = lots.get(0);
            return new Blend(Units.quantize(only.bushels(), Units.BUSHEL_PLACES), only.sample(), List.of(only.label()));
        }
        BigDecimal totalBushels = BigDecimal.ZERO;
        Set<Factor> factors = EnumSet.noneOf(Factor.class);
        for (Lot lot : lots) {
            totalBushels = totalBushels.add(lot.bushels());
            factors.addAll(lot.sample().keySet());
        }
        Map<Factor, BigDecimal> mixed = new LinkedHashMap<>();
        for (Factor factor : factors) {
            if (!lots.stream().allMatch(lot -> lot.sample().containsKey(factor))) {
                // A factor only some lots were tested for cannot be blended. The
                // blend is reported without it rather than with a guess.
                continue;
            }
            BigDecimal weighted = BigDecimal.ZERO;
            for (Lot lot : lots) {
                weighted = weighted.add(lot.sample().get(factor).multiply(lot.bushels()));
            }
            BigDecimal value = Decimals.divide(weighted, totalBushels);
            if (factor == Factor.TEST_WEIGHT) {
                value = value.subtract(TEST_WEIGHT_PENALTY);
            } else if (!BLENDS_BY_WEIGHT.contains(factor)) {
                continue;
            }
            mixed.put(factor, Units.quantize(value, SAMPLE_PLACES));
        }
        return new Blend(
                Units.quantize(totalBushels, Units.BUSHEL_PLACES),
                mixed,
                lots.stream().map(Lot::label).toList());
    }

    /** How many bushels of {@code clean} pull {@code poor} up to {@code target}, in hundreds. */
    public static Optional<BigDecimal> blendToGrade(Commodity commodity, Lot poor, Lot clean, NumericalGrade target) {
        return blendToGrade(commodity, poor, clean, target, DEFAULT_STEP);
    }

    /**
     * How many bushels of {@code clean} it takes to pull {@code poor} up to {@code target}.
     *
     * <p>Walks upward in steps rather than solving, because the grade ladder is a step function and
     * the algebra would have to be redone every time a limit moves. Nothing comes back if the clean
     * lot is not big enough to do it.
     */
    public static Optional<BigDecimal> blendToGrade(
            Commodity commodity, Lot poor, Lot clean, NumericalGrade target, BigDecimal step) {
        if (step.signum() <= 0) {
            throw new BlendError("a blending step has to be positive");
        }
        BigDecimal used = BigDecimal.ZERO;
        while (used.compareTo(clean.bushels()) <= 0) {
            Blend mixture = used.signum() > 0
                    ? blend(List.of(poor, new Lot(clean.label(), used, clean.sample())))
                    : blend(List.of(poor));
            GradeResult result;
            try {
                result = mixture.grade(commodity);
            } catch (BushelworksError ungradable) {
                throw new BlendError("cannot grade the blend: " + ungradable.getMessage(), ungradable);
            }
            if (!result.grade().isBetterThan(target) && result.grade() != target) {
                used = Units.quantize(used.add(step), Units.BUSHEL_PLACES);
                continue;
            }
            return Optional.of(used);
        }
        return Optional.empty();
    }

    /** What the blend is worth at the posted price for the grade it makes. */
    public static BigDecimal valueOf(Blend blended, Commodity commodity, Map<NumericalGrade, BigDecimal> prices) {
        GradeResult result = blended.grade(commodity);
        BigDecimal price = prices.get(result.grade());
        if (price == null) {
            throw new BlendError("no price posted for grade " + result.grade().value());
        }
        return Money.extend(blended.bushels(), price);
    }

    /**
     * Whether mixing beats selling the lots separately.
     *
     * <p>Almost always it does when one lot is off grade and almost never when both are on grade, but
     * the desk asks anyway and the arithmetic is cheap.
     */
    public static boolean isWorthBlending(Commodity commodity, List<Lot> lots, Map<NumericalGrade, BigDecimal> prices) {
        BigDecimal separately = BigDecimal.ZERO;
        for (Lot lot : lots) {
            separately = separately.add(valueOf(blend(List.of(lot)), commodity, prices));
        }
        return valueOf(blend(lots), commodity, prices).compareTo(separately) > 0;
    }
}
