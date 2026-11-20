package io.github.rkaushikdev.bushelworks.domain.grade;

import io.github.rkaushikdev.bushelworks.domain.commodity.Commodity;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Numerical grade determination from a probe sample.
 *
 * <p>The rule is worst factor: a load grades no better than its poorest factor, so a sample that
 * would be a No. 1 on everything except test weight is a No. 3 if the test weight only reaches the
 * No. 3 line. Nothing averages, nothing trades off.
 *
 * <p>The limits are the US standards the house grades against. They are stated as an ordered
 * ladder from best to worst, and determination walks the ladder from the top and stops at the first
 * rung the sample can stand on. A sample that falls off the bottom is Sample Grade, which is not a
 * grade so much as a refusal to give one.
 */
public final class Grading {

    private static final Map<String, List<GradeRung>> LADDERS = new ConcurrentHashMap<>();

    static {
        registerLadder(
                "CORN",
                List.of(
                                rung(NumericalGrade.US_1, "56.0", "0.1", "3.0", "2.0"),
                                rung(NumericalGrade.US_2, "54.0", "0.2", "5.0", "3.0"),
                                rung(NumericalGrade.US_3, "52.0", "0.5", "7.0", "4.0"),
                                rung(NumericalGrade.US_4, "49.0", "1.0", "10.0", "5.0"),
                                rung(NumericalGrade.US_5, "46.0", "3.0", "15.0", "7.0"))
                        .stream()
                        .map(limits ->
                                limits.named(Factor.TEST_WEIGHT, Factor.DAMAGE_HEAT, Factor.DAMAGE_TOTAL, Factor.BCFM))
                        .toList());
        registerLadder(
                "SOYB",
                List.of(
                                rung(NumericalGrade.US_1, "56.0", "0.2", "2.0", "1.0", "10.0"),
                                rung(NumericalGrade.US_2, "54.0", "0.5", "3.0", "2.0", "20.0"),
                                rung(NumericalGrade.US_3, "52.0", "1.0", "5.0", "3.0", "30.0"),
                                rung(NumericalGrade.US_4, "49.0", "3.0", "8.0", "5.0", "40.0"))
                        .stream()
                        .map(limits -> limits.named(
                                Factor.TEST_WEIGHT,
                                Factor.DAMAGE_HEAT,
                                Factor.DAMAGE_TOTAL,
                                Factor.FOREIGN_MATERIAL,
                                Factor.SPLITS))
                        .toList());
        registerLadder(
                "HRW",
                List.of(
                                rung(NumericalGrade.US_1, "60.0", "2.0", "3.0", "3.0"),
                                rung(NumericalGrade.US_2, "58.0", "4.0", "5.0", "5.0"),
                                rung(NumericalGrade.US_3, "56.0", "7.0", "8.0", "8.0"),
                                rung(NumericalGrade.US_4, "54.0", "10.0", "12.0", "12.0"),
                                rung(NumericalGrade.US_5, "51.0", "15.0", "20.0", "20.0"))
                        .stream()
                        .map(limits -> limits.named(
                                Factor.TEST_WEIGHT, Factor.DAMAGE_TOTAL, Factor.SHRUNKEN_BROKEN, Factor.TOTAL_DEFECTS))
                        .toList());
        registerLadder(
                "MILO",
                List.of(
                                rung(NumericalGrade.US_1, "57.0", "0.2", "2.0", "4.0"),
                                rung(NumericalGrade.US_2, "55.0", "0.5", "5.0", "8.0"),
                                rung(NumericalGrade.US_3, "53.0", "1.0", "10.0", "12.0"),
                                rung(NumericalGrade.US_4, "51.0", "3.0", "15.0", "15.0"))
                        .stream()
                        .map(limits -> limits.named(
                                Factor.TEST_WEIGHT, Factor.DAMAGE_HEAT, Factor.DAMAGE_TOTAL, Factor.FOREIGN_MATERIAL))
                        .toList());
        registerLadder(
                "SUNF",
                List.of(rung(NumericalGrade.US_1, "5.0", "3.0"), rung(NumericalGrade.US_2, "10.0", "6.0")).stream()
                        .map(limits -> limits.named(Factor.DAMAGE_TOTAL, Factor.FOREIGN_MATERIAL))
                        .toList());
    }

    private Grading() {}

    /** Put a ladder on file for a commodity, best rung first whatever order it was given in. */
    public static void registerLadder(String commodityCode, List<GradeRung> rungs) {
        List<GradeRung> ordered = new ArrayList<>(rungs);
        ordered.sort(Comparator.comparingInt(rung -> rung.grade().rank()));
        long distinct = ordered.stream().map(GradeRung::grade).distinct().count();
        if (distinct != ordered.size()) {
            throw new IllegalArgumentException(commodityCode + ": duplicate grade on the ladder");
        }
        LADDERS.put(commodityCode, List.copyOf(ordered));
    }

    /** The ladder for a commodity, best rung first. */
    public static List<GradeRung> ladder(String commodityCode) {
        List<GradeRung> rungs = LADDERS.get(commodityCode);
        if (rungs == null) {
            throw new NoGradeTable(commodityCode);
        }
        return rungs;
    }

    /** Every factor any rung of this ladder looks at. */
    public static Set<Factor> gradedFactors(String commodityCode) {
        Set<Factor> seen = EnumSet.noneOf(Factor.class);
        for (GradeRung rung : ladder(commodityCode)) {
            seen.addAll(rung.limits().keySet());
        }
        return Set.copyOf(seen);
    }

    private static void require(Commodity commodity, Map<Factor, BigDecimal> sample) {
        Set<Factor> missing = EnumSet.noneOf(Factor.class);
        missing.addAll(commodity.requiredFactors());
        missing.removeAll(sample.keySet());
        if (!missing.isEmpty()) {
            String names = missing.stream().map(Factor::value).sorted().collect(Collectors.joining(", "));
            throw new IncompleteSample(commodity.code() + ": sample is missing " + names);
        }
    }

    /**
     * Walk the ladder top down and stop at the first rung that holds.
     *
     * <p>The limiting factors are the ones that kept the sample off the rung above the one it
     * landed on, which is what the grader tells the driver when he asks why his corn is a three.
     */
    public static GradeResult determine(Commodity commodity, Map<Factor, BigDecimal> sample) {
        require(commodity, sample);
        List<GradeRung> rungs = ladder(commodity.code());

        GradeRung previous = null;
        for (GradeRung rung : rungs) {
            if (rung.accepts(sample)) {
                if (previous == null) {
                    return new GradeResult(rung.grade(), List.of());
                }
                return new GradeResult(rung.grade(), previous.failures(sample));
            }
            previous = rung;
        }
        GradeRung worst = rungs.get(rungs.size() - 1);
        return new GradeResult(NumericalGrade.SAMPLE, worst.failures(sample));
    }

    /**
     * Which factors stop this sample reaching {@code target}.
     *
     * <p>Used by the blending desk. If a bin is one factor away from a No. 2 there is often
     * something in the yard to blend it with, and knowing which factor is the difference between a
     * useful call and a guess.
     */
    public static List<Factor> factorsHoldingBack(
            Commodity commodity, Map<Factor, BigDecimal> sample, NumericalGrade target) {
        for (GradeRung rung : ladder(commodity.code())) {
            if (rung.grade() == target) {
                return rung.failures(sample);
            }
        }
        throw new NoGradeTable(commodity.code() + " has no rung for grade " + target.value());
    }

    private static Limits rung(NumericalGrade grade, String... values) {
        return new Limits(grade, values);
    }

    /** A rung's limits written as text, before the factors they belong to are named. */
    private record Limits(NumericalGrade grade, String[] values) {

        GradeRung named(Factor... factors) {
            Map<Factor, BigDecimal> limits = new LinkedHashMap<>();
            for (int index = 0; index < factors.length; index++) {
                limits.put(factors[index], new BigDecimal(values[index]));
            }
            return new GradeRung(grade, limits);
        }
    }
}
