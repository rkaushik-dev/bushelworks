package io.github.rkaushikdev.bushelworks.domain.grade;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.domain.commodity.Commodities;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GradeTest {

    static Map<Factor, BigDecimal> cornSample() {
        Map<Factor, BigDecimal> sample = new LinkedHashMap<>();
        sample.put(Factor.TEST_WEIGHT, d("57.0"));
        sample.put(Factor.MOISTURE, d("14.2"));
        sample.put(Factor.BCFM, d("1.1"));
        sample.put(Factor.DAMAGE_TOTAL, d("1.4"));
        sample.put(Factor.DAMAGE_HEAT, d("0.0"));
        return sample;
    }

    static Map<Factor, BigDecimal> cornSample(Factor factor, String value) {
        Map<Factor, BigDecimal> sample = cornSample();
        sample.put(factor, d(value));
        return sample;
    }

    @Test
    void cleanCornGradesOneWithNothingHoldingItBack() {
        GradeResult result = Grading.determine(Commodities.CORN, cornSample());
        assertThat(result.grade()).isSameAs(NumericalGrade.US_1);
        assertThat(result.limitingFactors()).isEmpty();
        assertThat(result.isSampleGrade()).isFalse();
    }

    @Test
    void worstFactorDecidesTheGrade() {
        // Everything is a No. 1 except test weight, which only reaches No. 3.
        GradeResult result = Grading.determine(Commodities.CORN, cornSample(Factor.TEST_WEIGHT, "52.5"));
        assertThat(result.grade()).isSameAs(NumericalGrade.US_3);
        assertThat(result.limitingFactors()).containsExactly(Factor.TEST_WEIGHT);
    }

    @Test
    void twoFactorsCanHoldALoadBackTogether() {
        Map<Factor, BigDecimal> sample = cornSample(Factor.TEST_WEIGHT, "53.0");
        sample.put(Factor.DAMAGE_TOTAL, d("6.2"));
        GradeResult result = Grading.determine(Commodities.CORN, sample);
        assertThat(result.grade()).isSameAs(NumericalGrade.US_3);
        assertThat(result.limitingFactors()).containsExactly(Factor.DAMAGE_TOTAL, Factor.TEST_WEIGHT);
    }

    @Test
    void limitIsInclusiveAtTheLine() {
        GradeResult exactlyTwo = Grading.determine(Commodities.CORN, cornSample(Factor.BCFM, "2.0"));
        assertThat(exactlyTwo.grade()).isSameAs(NumericalGrade.US_1);
        GradeResult justOver = Grading.determine(Commodities.CORN, cornSample(Factor.BCFM, "2.1"));
        assertThat(justOver.grade()).isSameAs(NumericalGrade.US_2);
    }

    @Test
    void testWeightIsInclusiveFromBelow() {
        GradeResult atLine = Grading.determine(Commodities.CORN, cornSample(Factor.TEST_WEIGHT, "56.0"));
        assertThat(atLine.grade()).isSameAs(NumericalGrade.US_1);
        GradeResult under = Grading.determine(Commodities.CORN, cornSample(Factor.TEST_WEIGHT, "55.9"));
        assertThat(under.grade()).isSameAs(NumericalGrade.US_2);
    }

    @Test
    void fallingOffTheBottomIsSampleGrade() {
        GradeResult result = Grading.determine(Commodities.CORN, cornSample(Factor.DAMAGE_TOTAL, "22.0"));
        assertThat(result.isSampleGrade()).isTrue();
        assertThat(result.limitingFactors()).contains(Factor.DAMAGE_TOTAL);
    }

    @Test
    void missingRequiredFactorIsRefused() {
        Map<Factor, BigDecimal> sample = cornSample();
        sample.remove(Factor.BCFM);
        assertThatThrownBy(() -> Grading.determine(Commodities.CORN, sample)).isInstanceOf(IncompleteSample.class);
    }

    @Test
    void optionalFactorMayBeAbsent() {
        Map<Factor, BigDecimal> sample = cornSample();
        sample.remove(Factor.DAMAGE_HEAT);
        assertThat(Grading.determine(Commodities.CORN, sample).grade()).isSameAs(NumericalGrade.US_1);
    }

    @Test
    void soybeanSplitsLadder() {
        Map<Factor, BigDecimal> sample = new LinkedHashMap<>();
        sample.put(Factor.TEST_WEIGHT, d("56.5"));
        sample.put(Factor.MOISTURE, d("12.4"));
        sample.put(Factor.FOREIGN_MATERIAL, d("0.6"));
        sample.put(Factor.DAMAGE_TOTAL, d("1.2"));
        sample.put(Factor.SPLITS, d("24.0"));
        GradeResult result = Grading.determine(Commodities.SOYBEANS, sample);
        assertThat(result.grade()).isSameAs(NumericalGrade.US_3);
        assertThat(result.limitingFactors()).containsExactly(Factor.SPLITS);
    }

    @Test
    void soybeansStopAtGradeFour() {
        List<NumericalGrade> grades =
                Grading.ladder("SOYB").stream().map(GradeRung::grade).toList();
        assertThat(grades).doesNotContain(NumericalGrade.US_5);
        assertThat(grades.get(grades.size() - 1)).isSameAs(NumericalGrade.US_4);
    }

    @Test
    void factorsHoldingBackATargetGrade() {
        Map<Factor, BigDecimal> sample = cornSample(Factor.TEST_WEIGHT, "53.0");
        assertThat(Grading.factorsHoldingBack(Commodities.CORN, sample, NumericalGrade.US_1))
                .containsExactly(Factor.TEST_WEIGHT);
        assertThat(Grading.factorsHoldingBack(Commodities.CORN, sample, NumericalGrade.US_3))
                .isEmpty();
    }

    @Test
    void gradedFactorsReportsTheLadderColumns() {
        assertThat(Grading.gradedFactors("CORN"))
                .isEqualTo(Set.of(Factor.TEST_WEIGHT, Factor.DAMAGE_HEAT, Factor.DAMAGE_TOTAL, Factor.BCFM));
    }

    @Test
    void gradeOrdering() {
        assertThat(NumericalGrade.US_1.isBetterThan(NumericalGrade.US_2)).isTrue();
        assertThat(NumericalGrade.US_4.isBetterThan(NumericalGrade.US_2)).isFalse();
        assertThat(NumericalGrade.US_5.isBetterThan(NumericalGrade.SAMPLE)).isTrue();
    }

    @Test
    void theFiledCornTableReadsBackAsTheStandardsLadder() {
        List<GradeRung> rungs = GradeTable.parse("CORN", Grading.CORN);
        assertThat(rungs).isEqualTo(Grading.ladder("CORN"));
        assertThat(rungs.get(0).limits().keySet())
                .containsExactly(Factor.TEST_WEIGHT, Factor.DAMAGE_HEAT, Factor.DAMAGE_TOTAL, Factor.BCFM);
        assertThat(rungs.get(2).limits()).containsEntry(Factor.TEST_WEIGHT, d("52.0"));
        assertThat(rungs.get(4).limits()).containsEntry(Factor.BCFM, d("7.0"));
    }

    @Test
    void everyFiledLadderLoosensFromTheTopDown() {
        for (String code : List.of("CORN", "SOYB", "HRW", "MILO", "SUNF")) {
            assertThat(Grading.ladder(code)).as(code).isNotEmpty();
        }
        assertThat(Grading.ladder("SUNF"))
                .extracting(GradeRung::grade)
                .containsExactly(NumericalGrade.US_1, NumericalGrade.US_2);
    }

    @Test
    void aHouseCanFileItsOwnLadderAsText() {
        Grading.registerLadder(
                "WAXY",
                """
                # a processor's contract grades, test weight only on the top rung
                grade  test_weight  damage_total
                2      54.0         5.0
                1      56.0         3.0
                3      -            7.0
                """);
        List<GradeRung> rungs = Grading.ladder("WAXY");
        assertThat(rungs)
                .extracting(GradeRung::grade)
                .containsExactly(NumericalGrade.US_1, NumericalGrade.US_2, NumericalGrade.US_3);
        assertThat(rungs.get(2).limits()).containsOnlyKeys(Factor.DAMAGE_TOTAL);
    }

    @Test
    void aLowerGradeStricterThanTheOneAboveIsATypingMistake() {
        assertThatThrownBy(
                        () -> GradeTable.parse(
                                "CORN",
                                """
                        grade  test_weight  bcfm
                        1      56.0         2.0
                        2      57.0         3.0
                        """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("US 2 is stricter than US 1 on test_weight");
        assertThatThrownBy(
                        () -> GradeTable.parse(
                                "CORN",
                                """
                        grade  test_weight  bcfm
                        1      56.0         3.0
                        2      54.0         2.0
                        """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("on bcfm");
    }

    @Test
    void aTableThatCannotBeReadIsRefused() {
        assertThatThrownBy(() -> GradeTable.parse("CORN", "grade  test_weight"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GradeTable.parse("CORN", "factor  test_weight\n1  56.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grade column");
        assertThatThrownBy(() -> GradeTable.parse("CORN", "grade  test_weight  bcfm\n1  56.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 limits for 2 factors");
        assertThatThrownBy(() -> GradeTable.parse("CORN", "grade  colour\n1  56.0"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void twoPiecesOfGlassMakeANumberOneSampleGrade() {
        GradeResult result = Grading.determine(Commodities.CORN, cornSample(Factor.GLASS, "2"));
        assertThat(result.grade()).isSameAs(NumericalGrade.SAMPLE);
        assertThat(result.limitingFactors()).containsExactly(Factor.GLASS);
        assertThat(Grading.determine(Commodities.CORN, cornSample(Factor.GLASS, "1"))
                        .grade())
                .isSameAs(NumericalGrade.US_1);
    }

    @Test
    void stonesAreJudgedOnTheirShareOfTheSampleWeight() {
        assertThat(Grading.determine(Commodities.CORN, cornSample(Factor.STONES, "0.1"))
                        .grade())
                .isSameAs(NumericalGrade.US_1);
        assertThat(Grading.determine(Commodities.CORN, cornSample(Factor.STONES, "0.11"))
                        .grade())
                .isSameAs(NumericalGrade.SAMPLE);
    }

    @Test
    void everyBrokenCriterionIsNamedInOrder() {
        Map<Factor, BigDecimal> sample = cornSample(Factor.TEST_WEIGHT, "50.0");
        sample.put(Factor.CASTOR_BEANS, d("2"));
        sample.put(Factor.ANIMAL_FILTH, d("0.25"));
        sample.put(Factor.CROTALARIA, d("2"));
        GradeResult result = Grading.determine(Commodities.CORN, sample);
        assertThat(result.isSampleGrade()).isTrue();
        assertThat(result.limitingFactors()).containsExactly(Factor.ANIMAL_FILTH, Factor.CASTOR_BEANS);
    }

    @Test
    void aGraderMayRecordTheCriteriaOnEveryCommodity() {
        for (String code : List.of("CORN", "SOYB", "HRW", "MILO", "SUNF")) {
            assertThat(Commodities.get(code).gradedFactors()).as(code).containsAll(SampleGradeCriteria.FACTORS);
            assertThat(Commodities.get(code).requiredFactors()).as(code).doesNotContain(Factor.GLASS);
        }
        assertThat(Factor.of("unknown_foreign_substance")).isSameAs(Factor.UNKNOWN_FOREIGN);
    }

    @Test
    void aCommodityCanFileCriteriaOfItsOwn() {
        assertThat(Grading.criteria("MILO")).isEqualTo(SampleGradeCriteria.STANDARD);
        Grading.registerCriteria("WAXY", new SampleGradeCriteria(Map.of(Factor.GLASS, d("0"))));
        assertThat(Grading.criteria("WAXY").failures(Map.of(Factor.GLASS, d("1"), Factor.STONES, d("5"))))
                .containsExactly(Factor.GLASS);
    }

    @Test
    void aLadderFactorIsNotACriterion() {
        assertThatThrownBy(() -> new SampleGradeCriteria(Map.of(Factor.BCFM, d("5"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SampleGradeCriteria(Map.of(Factor.GLASS, d("-1"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownLadderRaises() {
        assertThatThrownBy(() -> Grading.ladder("BARLEY")).isInstanceOf(NoGradeTable.class);
    }
}
