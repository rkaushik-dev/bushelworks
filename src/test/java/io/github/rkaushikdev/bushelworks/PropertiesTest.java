package io.github.rkaushikdev.bushelworks;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.bushelworks.domain.moisture.ShrinkMethod;
import io.github.rkaushikdev.bushelworks.domain.moisture.ShrinkSchedule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Properties that have to hold for every input, not just the worked examples.
 *
 * <p>The example based tests check the numbers the office would tie out by hand. These check the
 * things that have to be true whatever comes in, and they have already found two: an allocation that
 * lost a cent on a three way split of an odd amount, and a shrink that could return more dry bushels
 * than went in when the moisture came in exactly at base.
 *
 * <p>Each property runs over the edges of its range and two hundred values drawn from a seeded
 * generator, so a failure names an input that reproduces on every run.
 */
class PropertiesTest {

    static final int CASES = 200;

    /** The standard test weights of the commodities the house buys, in pounds per bushel. */
    static final List<BigDecimal> TEST_WEIGHTS = List.of(d("25"), d("32"), d("56"), d("60"));

    static final List<BigDecimal> SHRINK_FACTORS = List.of(d("1.15"), d("1.18"));

    static final BigDecimal WET = d("1000.0000");

    /** {@link #CASES} draws, after the edge values every run should see. */
    static Stream<Arguments> cases(List<Arguments> edges, Supplier<Arguments> draw) {
        List<Arguments> all = new ArrayList<>(edges);
        for (int index = 0; index < CASES; index++) {
            all.add(draw.get());
        }
        return all.stream();
    }

    /** Weights a truck scale can actually report. Six digits, whole pounds. */
    static BigDecimal pounds(Random random) {
        return BigDecimal.valueOf(random.nextLong(0, 1_000_000));
    }

    /** Money in a range a settlement reaches, at cents. */
    static BigDecimal amount(Random random) {
        return BigDecimal.valueOf(random.nextLong(-100_000_000L, 100_000_001L), 2);
    }

    static BigDecimal testWeight(Random random) {
        return TEST_WEIGHTS.get(random.nextInt(TEST_WEIGHTS.size()));
    }

    /** A moisture reading from bone dry to forty percent, to a tenth. */
    static BigDecimal moisture(Random random) {
        return BigDecimal.valueOf(random.nextInt(0, 401), 1);
    }

    static ShrinkSchedule cornShrink(BigDecimal factor) {
        return ShrinkSchedule.of("CORN", d("15.0"))
                .withMethod(ShrinkMethod.TABLE)
                .withShrinkFactor(factor);
    }

    static boolean atCents(BigDecimal value) {
        return value.compareTo(value.setScale(2, RoundingMode.HALF_EVEN)) == 0;
    }

    static List<BigDecimal> equalWeights(int count) {
        return Collections.nCopies(count, BigDecimal.ONE);
    }

    static Stream<Arguments> grossAndTare() {
        Random random = new Random(101);
        List<Arguments> edges = List.of(
                Arguments.of(d("0"), d("0")),
                Arguments.of(d("999999"), d("0")),
                Arguments.of(d("999999"), d("999999")),
                Arguments.of(d("1"), d("1")));
        return cases(edges, () -> {
            BigDecimal one = pounds(random);
            BigDecimal other = pounds(random);
            return one.compareTo(other) >= 0 ? Arguments.of(one, other) : Arguments.of(other, one);
        });
    }

    static Stream<Arguments> weightAndStandard() {
        Random random = new Random(202);
        List<Arguments> edges = new ArrayList<>();
        for (BigDecimal standard : TEST_WEIGHTS) {
            edges.add(Arguments.of(d("0"), standard));
            edges.add(Arguments.of(d("1"), standard));
            edges.add(Arguments.of(d("999999"), standard));
        }
        return cases(edges, () -> Arguments.of(pounds(random), testWeight(random)));
    }

    static Stream<Arguments> amountAndShareCount() {
        Random random = new Random(303);
        List<Arguments> edges = new ArrayList<>();
        for (int count = 1; count <= 6; count++) {
            for (String value : List.of("0.00", "0.01", "-0.01", "1000000.00", "-1000000.00", "0.05", "-0.05")) {
                edges.add(Arguments.of(d(value), count));
            }
        }
        return cases(edges, () -> Arguments.of(amount(random), random.nextInt(1, 7)));
    }

    static Stream<Arguments> amountAndFirstShare() {
        Random random = new Random(404);
        List<Arguments> edges = List.of(
                Arguments.of(d("0.01"), 1),
                Arguments.of(d("0.01"), 99),
                Arguments.of(d("-0.01"), 50),
                Arguments.of(d("1000000.00"), 33),
                Arguments.of(d("-1000000.00"), 67),
                Arguments.of(d("0.00"), 1));
        return cases(edges, () -> Arguments.of(amount(random), random.nextInt(1, 100)));
    }

    static Stream<Arguments> amountLists() {
        Random random = new Random(505);
        List<Arguments> edges = List.of(
                Arguments.of(List.of()),
                Arguments.of(List.of(d("0.00"))),
                Arguments.of(List.of(d("1000000.00"), d("-1000000.00"), d("0.01"))));
        return cases(edges, () -> {
            List<BigDecimal> values = new ArrayList<>();
            int size = random.nextInt(0, 21);
            for (int index = 0; index < size; index++) {
                values.add(amount(random));
            }
            return Arguments.of(values);
        });
    }

    static Stream<Arguments> quantityAndRate() {
        Random random = new Random(606);
        List<Arguments> edges = List.of(
                Arguments.of(d("0.0000"), d("0.0000")),
                Arguments.of(d("100000.0000"), d("50.0000")),
                Arguments.of(d("0.0001"), d("0.0001")),
                Arguments.of(d("0.5000"), d("0.0100")),
                Arguments.of(d("100000.0000"), d("0.0000")));
        return cases(
                edges,
                () -> Arguments.of(
                        BigDecimal.valueOf(random.nextLong(0, 1_000_000_001L), 4),
                        BigDecimal.valueOf(random.nextLong(0, 500_001L), 4)));
    }

    static Stream<Arguments> moistureAndFactor() {
        Random random = new Random(707);
        List<Arguments> edges = new ArrayList<>();
        for (BigDecimal factor : SHRINK_FACTORS) {
            for (String reading : List.of("0.0", "14.9", "15.0", "15.1", "40.0")) {
                edges.add(Arguments.of(d(reading), factor));
            }
        }
        return cases(
                edges, () -> Arguments.of(moisture(random), SHRINK_FACTORS.get(random.nextInt(SHRINK_FACTORS.size()))));
    }

    static Stream<Arguments> moistures() {
        Random random = new Random(808);
        List<Arguments> edges = List.of(
                Arguments.of(d("0.0")),
                Arguments.of(d("15.0")),
                Arguments.of(d("15.1")),
                Arguments.of(d("38.9")),
                Arguments.of(d("40.0")));
        return cases(edges, () -> Arguments.of(moisture(random)));
    }

    static Stream<Arguments> moisturesBelowThirtyNine() {
        return moistures().filter(each -> ((BigDecimal) each.get()[0]).compareTo(d("39")) < 0);
    }

    static Stream<Arguments> sixPlaceValues() {
        Random random = new Random(909);
        List<Arguments> edges = List.of(
                Arguments.of(d("0.000000")),
                Arguments.of(d("-1000.000000")),
                Arguments.of(d("1000.000000")),
                Arguments.of(d("0.000050")),
                Arguments.of(d("-0.000050")),
                Arguments.of(d("2.000049")));
        return cases(
                edges, () -> Arguments.of(BigDecimal.valueOf(random.nextLong(-1_000_000_000L, 1_000_000_001L), 6)));
    }

    @ParameterizedTest
    @MethodSource("grossAndTare")
    void netWeightIsNeverMoreThanGross(BigDecimal gross, BigDecimal tare) {
        assertThat(Units.netPounds(gross, tare)).isLessThanOrEqualTo(gross);
    }

    @ParameterizedTest
    @MethodSource("grossAndTare")
    void netWeightIsNeverNegative(BigDecimal gross, BigDecimal tare) {
        assertThat(Units.netPounds(gross, tare).signum()).isNotNegative();
    }

    @ParameterizedTest
    @MethodSource("weightAndStandard")
    void bushelsAreNeverNegative(BigDecimal weight, BigDecimal standard) {
        assertThat(Units.poundsToBushels(weight, standard).signum()).isNotNegative();
    }

    @ParameterizedTest
    @MethodSource("weightAndStandard")
    void convertingBackLandsWithinABushel(BigDecimal weight, BigDecimal standard) {
        BigDecimal bushels = Units.poundsToBushels(weight, standard);
        BigDecimal back = Units.bushelsToPounds(bushels, standard);
        assertThat(back.subtract(weight).abs()).isLessThanOrEqualTo(standard);
    }

    @ParameterizedTest
    @MethodSource("weightAndStandard")
    void aHeavierLoadIsNeverFewerBushels(BigDecimal weight, BigDecimal standard) {
        BigDecimal heavier = weight.add(d("1000"));
        assertThat(Units.poundsToBushels(heavier, standard))
                .isGreaterThanOrEqualTo(Units.poundsToBushels(weight, standard));
    }

    @ParameterizedTest
    @MethodSource("amountAndShareCount")
    void anAllocationAlwaysAddsBackToTheWhole(BigDecimal value, int count) {
        List<BigDecimal> parts = Money.allocate(value, equalWeights(count));
        assertThat(TestDecimals.sum(parts)).isEqualByComparingTo(Money.amount(value));
    }

    @ParameterizedTest
    @MethodSource("amountAndFirstShare")
    void aTwoWayAllocationAddsBackWhateverTheSplit(BigDecimal value, int first) {
        List<BigDecimal> parts =
                Money.allocate(value, List.of(BigDecimal.valueOf(first), BigDecimal.valueOf(100 - first)));
        assertThat(TestDecimals.sum(parts)).isEqualByComparingTo(Money.amount(value));
    }

    @ParameterizedTest
    @MethodSource("amountAndShareCount")
    void everyPartOfAnAllocationIsAtCents(BigDecimal value, int count) {
        assertThat(Money.allocate(value, equalWeights(count)))
                .allSatisfy(part ->
                        assertThat(atCents(part)).as("%s is at cents", part).isTrue());
    }

    @ParameterizedTest
    @MethodSource("amountAndShareCount")
    void anAllocationNeverFlipsTheSign(BigDecimal value, int count) {
        List<BigDecimal> parts = Money.allocate(value, equalWeights(count));
        if (value.signum() > 0) {
            assertThat(parts).allSatisfy(part -> assertThat(part.signum()).isNotNegative());
        } else if (value.signum() < 0) {
            assertThat(parts).allSatisfy(part -> assertThat(part.signum()).isNotPositive());
        } else {
            assertThat(parts).allSatisfy(part -> assertThat(part.signum()).isZero());
        }
    }

    @ParameterizedTest
    @MethodSource("amountLists")
    void aTotalIsTheSumOfItsParts(List<BigDecimal> values) {
        assertThat(Money.total(values)).isEqualByComparingTo(Money.amount(TestDecimals.sum(values)));
    }

    @ParameterizedTest
    @MethodSource("quantityAndRate")
    void extendingIsNeverNegativeForPositiveInputs(BigDecimal quantity, BigDecimal rate) {
        assertThat(Money.extend(quantity, rate).signum()).isNotNegative();
    }

    @ParameterizedTest
    @MethodSource("quantityAndRate")
    void extendingLandsAtCents(BigDecimal quantity, BigDecimal rate) {
        assertThat(atCents(Money.extend(quantity, rate))).isTrue();
    }

    @ParameterizedTest
    @MethodSource("moistureAndFactor")
    void shrinkNeverMakesMoreBushelsThanWentIn(BigDecimal moisture, BigDecimal factor) {
        assertThat(cornShrink(factor).dryBushels(WET, moisture)).isLessThanOrEqualTo(WET);
    }

    @ParameterizedTest
    @MethodSource("moistures")
    void dryAndShrunkAlwaysAddBackToWet(BigDecimal moisture) {
        ShrinkSchedule schedule = cornShrink(d("1.18"));
        BigDecimal dry = schedule.dryBushels(WET, moisture);
        BigDecimal gone = schedule.shrunkBushels(WET, moisture);
        assertThat(dry.add(gone)).isEqualByComparingTo(WET);
    }

    @ParameterizedTest
    @MethodSource("moisturesBelowThirtyNine")
    void wetterGrainNeverShrinksLess(BigDecimal moisture) {
        ShrinkSchedule schedule = cornShrink(d("1.18"));
        BigDecimal wetter = moisture.add(BigDecimal.ONE);
        assertThat(schedule.shrinkPercent(wetter)).isGreaterThanOrEqualTo(schedule.shrinkPercent(moisture));
    }

    @ParameterizedTest
    @MethodSource("sixPlaceValues")
    void quantizingIsIdempotent(BigDecimal value) {
        BigDecimal once = Units.quantize(value, Units.BUSHEL_PLACES);
        assertThat(Units.quantize(once, Units.BUSHEL_PLACES)).isEqualByComparingTo(once);
    }
}
