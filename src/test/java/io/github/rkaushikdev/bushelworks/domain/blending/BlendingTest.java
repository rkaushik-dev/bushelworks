package io.github.rkaushikdev.bushelworks.domain.blending;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.Decimals;
import io.github.rkaushikdev.bushelworks.domain.commodity.Commodities;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.domain.grade.GradeResult;
import io.github.rkaushikdev.bushelworks.domain.grade.NumericalGrade;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BlendingTest {

    static final Map<NumericalGrade, BigDecimal> PRICES = Map.of(
            NumericalGrade.US_1, d("4.4000"),
            NumericalGrade.US_2, d("4.3500"),
            NumericalGrade.US_3, d("4.2000"),
            NumericalGrade.US_4, d("3.9000"),
            NumericalGrade.US_5, d("3.5000"),
            NumericalGrade.SAMPLE, d("3.0000"));

    static Map<Factor, BigDecimal> sample(String testWeight, String damage) {
        Map<Factor, BigDecimal> sample = new LinkedHashMap<>();
        sample.put(Factor.TEST_WEIGHT, d(testWeight));
        sample.put(Factor.MOISTURE, d("14.0"));
        sample.put(Factor.BCFM, d("1.2"));
        sample.put(Factor.DAMAGE_TOTAL, d(damage));
        sample.put(Factor.DAMAGE_HEAT, d("0.0"));
        return sample;
    }

    static Map<Factor, BigDecimal> sample() {
        return sample("56.5", "1.0");
    }

    static final Lot CLEAN = new Lot("bin 4", d("4000"), sample());
    static final Lot POOR = new Lot("bin 7", d("2000"), sample("53.0", "6.5"));

    @Test
    void aLotNeedsBushels() {
        assertThatThrownBy(() -> new Lot("bin 1", d("0"), sample())).isInstanceOf(BlendError.class);
    }

    @Test
    void aLotNeedsASample() {
        assertThatThrownBy(() -> new Lot("bin 1", d("1000"), Map.of())).isInstanceOf(BlendError.class);
    }

    @Test
    void blendingNothingIsRefused() {
        assertThatThrownBy(() -> Blending.blend(List.of())).isInstanceOf(BlendError.class);
    }

    @Test
    void oneLotBlendsToItself() {
        Blend mixture = Blending.blend(List.of(CLEAN));
        assertThat(mixture.bushels()).isEqualTo(d("4000.0000"));
        assertThat(mixture.sample()).isEqualTo(CLEAN.sample());
        assertThat(mixture.lots()).containsExactly("bin 4");
    }

    @Test
    void percentageFactorsBlendByWeight() {
        // 4000 at 1.0 and 2000 at 6.5 gives 2.83 rounded
        assertThat(Blending.blend(List.of(CLEAN, POOR)).sample().get(Factor.DAMAGE_TOTAL))
                .isEqualTo(d("2.83"));
    }

    @Test
    void theBlendCarriesEveryLotLabel() {
        assertThat(Blending.blend(List.of(CLEAN, POOR)).lots()).containsExactly("bin 4", "bin 7");
    }

    @Test
    void theBushelsAddUp() {
        assertThat(Blending.blend(List.of(CLEAN, POOR)).bushels()).isEqualTo(d("6000.0000"));
    }

    @Test
    void testWeightComesInBelowTheWeightedAverage() {
        Blend mixture = Blending.blend(List.of(CLEAN, POOR));
        BigDecimal weighted =
                Decimals.divide(d("56.5").multiply(d("4000")).add(d("53.0").multiply(d("2000"))), d("6000"));
        BigDecimal expected = weighted.subtract(Blending.TEST_WEIGHT_PENALTY).setScale(2, RoundingMode.HALF_EVEN);
        assertThat(mixture.sample().get(Factor.TEST_WEIGHT)).isEqualTo(expected);
    }

    @Test
    void aFactorOnlyOneLotWasTestedForIsLeftOut() {
        Map<Factor, BigDecimal> partialSample = new LinkedHashMap<>();
        partialSample.put(Factor.TEST_WEIGHT, d("56.0"));
        partialSample.put(Factor.MOISTURE, d("14.0"));
        partialSample.put(Factor.BCFM, d("1.0"));
        partialSample.put(Factor.DAMAGE_TOTAL, d("1.0"));
        Lot partial = new Lot("bin 9", d("1000"), partialSample);
        assertThat(Blending.blend(List.of(CLEAN, partial)).sample()).doesNotContainKey(Factor.DAMAGE_HEAT);
    }

    @Test
    void aBlendGradesWorseThanTheCleanLotAlone() {
        GradeResult clean = Blending.blend(List.of(CLEAN)).grade(Commodities.CORN);
        GradeResult mixed = Blending.blend(List.of(CLEAN, POOR)).grade(Commodities.CORN);
        assertThat(clean.grade().isBetterThan(mixed.grade())).isTrue();
    }

    @Test
    void aBlendGradesBetterThanThePoorLotAlone() {
        GradeResult poor = Blending.blend(List.of(POOR)).grade(Commodities.CORN);
        GradeResult mixed = Blending.blend(List.of(CLEAN, POOR)).grade(Commodities.CORN);
        assertThat(mixed.grade().isBetterThan(poor.grade())).isTrue();
    }

    @Test
    void blendingToATargetFindsTheBushelsNeeded() {
        Optional<BigDecimal> needed = Blending.blendToGrade(Commodities.CORN, POOR, CLEAN, NumericalGrade.US_2);
        assertThat(needed).isPresent();
        assertThat(needed.get()).isPositive();
    }

    @Test
    void aLotAlreadyAtTheTargetNeedsNothing() {
        assertThat(Blending.blendToGrade(Commodities.CORN, CLEAN, CLEAN, NumericalGrade.US_2))
                .hasValueSatisfying(used -> assertThat(used).isEqualByComparingTo(d("0")));
    }

    @Test
    void aCleanLotTooSmallToHelpGivesNothing() {
        Lot tiny = new Lot("bin 2", d("50"), sample());
        assertThat(Blending.blendToGrade(Commodities.CORN, POOR, tiny, NumericalGrade.US_1))
                .isEmpty();
    }

    @Test
    void aBlendingStepHasToBePositive() {
        assertThatThrownBy(() -> Blending.blendToGrade(Commodities.CORN, POOR, CLEAN, NumericalGrade.US_2, d("0")))
                .isInstanceOf(BlendError.class);
    }

    @Test
    void aBlendIsValuedAtThePriceForItsGrade() {
        assertThat(Blending.valueOf(Blending.blend(List.of(CLEAN)), Commodities.CORN, PRICES))
                .isEqualTo(d("17600.00"));
    }

    @Test
    void aGradeWithNoPostedPriceIsRefused() {
        assertThatThrownBy(() -> Blending.valueOf(
                        Blending.blend(List.of(CLEAN)), Commodities.CORN, Map.of(NumericalGrade.US_5, d("3.50"))))
                .isInstanceOf(BlendError.class);
    }

    @Test
    void blendingAPoorLotWithACleanOneIsWorthDoing() {
        assertThat(Blending.isWorthBlending(Commodities.CORN, List.of(CLEAN, POOR), PRICES))
                .isTrue();
    }

    @Test
    void blendingTwoCleanLotsIsNotWorthDoing() {
        Lot other = new Lot("bin 5", d("3000"), sample());
        assertThat(Blending.isWorthBlending(Commodities.CORN, List.of(CLEAN, other), PRICES))
                .isFalse();
    }

    @Test
    void theBlendOfThreeLotsStillAddsUp() {
        Lot third = new Lot("bin 5", d("1500"), sample("56.5", "2.0"));
        Blend mixture = Blending.blend(List.of(CLEAN, POOR, third));
        assertThat(mixture.bushels()).isEqualTo(d("7500.0000"));
        assertThat(mixture.lots()).hasSize(3);
    }
}
