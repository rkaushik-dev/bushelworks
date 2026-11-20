package io.github.rkaushikdev.bushelworks.domain.inspection;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.domain.grade.GradeResult;
import io.github.rkaushikdev.bushelworks.domain.grade.NumericalGrade;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class InspectionTest {

    static final LocalDate SAMPLED = LocalDate.of(2024, 11, 12);
    static final LocalDate ISSUED = LocalDate.of(2024, 11, 13);
    static final Map<NumericalGrade, BigDecimal> PRICES = Map.of(
            NumericalGrade.US_1, d("4.4000"),
            NumericalGrade.US_2, d("4.3500"),
            NumericalGrade.US_3, d("4.2000"),
            NumericalGrade.SAMPLE, d("3.0000"));

    static Certificate.Builder aCertificate() {
        Map<Factor, BigDecimal> factors = new LinkedHashMap<>();
        factors.put(Factor.TEST_WEIGHT, d("55.1"));
        factors.put(Factor.MOISTURE, d("14.4"));
        factors.put(Factor.BCFM, d("2.6"));
        factors.put(Factor.DAMAGE_TOTAL, d("3.1"));
        return Certificate.builder()
                .number("KS-2024-114772")
                .agency("Kansas Grain Inspection Service")
                .commodityCode("CORN")
                .sampledOn(SAMPLED)
                .issuedOn(ISSUED)
                .grade(NumericalGrade.US_2)
                .factors(factors);
    }

    static GradeResult house(NumericalGrade grade) {
        return new GradeResult(grade, List.of());
    }

    @Test
    void aCertificateNeedsANumberAndAnAgency() {
        assertThatThrownBy(() -> aCertificate().number("").build()).isInstanceOf(InspectionError.class);
        assertThatThrownBy(() -> aCertificate().agency("").build()).isInstanceOf(InspectionError.class);
    }

    @Test
    void aCertificateCannotBeIssuedBeforeTheSample() {
        assertThatThrownBy(
                        () -> aCertificate().issuedOn(LocalDate.of(2024, 11, 1)).build())
                .isInstanceOf(InspectionError.class);
    }

    @Test
    void aCertificateStandsForThirtyDaysFromTheSample() {
        Certificate certificate = aCertificate().build();
        assertThat(certificate.expiresOn()).isEqualTo(SAMPLED.plusDays(Certificate.CERTIFICATE_LIFE_DAYS));
        assertThat(certificate.isCurrentOn(LocalDate.of(2024, 12, 1))).isTrue();
        assertThat(certificate.isCurrentOn(LocalDate.of(2024, 12, 20))).isFalse();
    }

    @Test
    void aCertificateIsNotCurrentBeforeItIsIssued() {
        assertThat(aCertificate().build().isCurrentOn(SAMPLED)).isFalse();
    }

    @Test
    void aPlainCertificateDescribesItselfByItsNumber() {
        assertThat(aCertificate().build().description()).isEqualTo("US No. 2 CORN");
    }

    @Test
    void sampleGradeIsWrittenOutRatherThanNumbered() {
        assertThat(aCertificate().grade(NumericalGrade.SAMPLE).build().description())
                .isEqualTo("Sample Grade CORN");
    }

    @Test
    void aSpecialGradeGoesAfterTheNumber() {
        assertThat(aCertificate()
                        .specialGrades(Set.of(SpecialGrade.MUSTY))
                        .build()
                        .description())
                .isEqualTo("US No. 2 CORN, musty");
    }

    @Test
    void twoSpecialGradesAreListedInOrder() {
        assertThat(aCertificate()
                        .specialGrades(Set.of(SpecialGrade.WEEVILY, SpecialGrade.MUSTY))
                        .build()
                        .description())
                .isEqualTo("US No. 2 CORN, musty, weevily");
    }

    @Test
    void anOrdinaryCertificateIsNotRejectable() {
        assertThat(aCertificate().build().isRejectable()).isFalse();
        assertThat(aCertificate()
                        .specialGrades(Set.of(SpecialGrade.MUSTY))
                        .build()
                        .isRejectable())
                .isFalse();
    }

    @Test
    void aLoadOnGradeCanStillBeRefusedForASpecialGrade() {
        Certificate certificate = aCertificate()
                .grade(NumericalGrade.US_2)
                .specialGrades(Set.of(SpecialGrade.WEEVILY))
                .build();
        assertThat(certificate.grade()).isSameAs(NumericalGrade.US_2);
        assertThat(certificate.isRejectable()).isTrue();
    }

    @Test
    void theFactorsReadBack() {
        Certificate certificate = aCertificate().build();
        assertThat(certificate.factor(Factor.TEST_WEIGHT)).contains(d("55.1"));
        assertThat(certificate.factor(Factor.PROTEIN)).isEmpty();
    }

    @Test
    void agreeingGradesAreNoDisagreement() {
        assertThat(Inspection.compare(house(NumericalGrade.US_2), aCertificate().build()))
                .isEmpty();
    }

    @Test
    void aDifferentGradeIsADisagreement() {
        List<Disagreement> found =
                Inspection.compare(house(NumericalGrade.US_1), aCertificate().build());
        assertThat(found).hasSize(1);
        assertThat(found.get(0).isGrade()).isTrue();
        assertThat(found.get(0).house()).isEqualTo("1");
        assertThat(found.get(0).official()).isEqualTo("2");
    }

    @Test
    void factorsInsideTheToleranceDoNotDisagree() {
        Map<Factor, BigDecimal> houseSample = Map.of(Factor.TEST_WEIGHT, d("55.4"), Factor.MOISTURE, d("14.2"));
        Map<Factor, BigDecimal> tolerance = Map.of(Factor.TEST_WEIGHT, d("0.5"), Factor.MOISTURE, d("0.3"));
        assertThat(Inspection.compareFactors(houseSample, aCertificate().build(), tolerance))
                .isEmpty();
    }

    @Test
    void aFactorOutsideTheToleranceDisagrees() {
        List<Disagreement> found = Inspection.compareFactors(
                Map.of(Factor.TEST_WEIGHT, d("56.5")), aCertificate().build(), Map.of(Factor.TEST_WEIGHT, d("0.5")));
        assertThat(found).extracting(Disagreement::factor).containsExactly(Optional.of(Factor.TEST_WEIGHT));
    }

    @Test
    void aFactorTheHouseNeverMeasuredIsNotADisagreement() {
        Certificate certificate = aCertificate()
                .factors(Map.of(Factor.PROTEIN, d("11.4"), Factor.MOISTURE, d("14.4")))
                .build();
        assertThat(Inspection.compareFactors(Map.of(Factor.MOISTURE, d("14.4")), certificate))
                .isEmpty();
    }

    @Test
    void withNoToleranceAnyDifferenceDisagrees() {
        List<Disagreement> found = Inspection.compareFactors(
                Map.of(Factor.MOISTURE, d("14.5")), aCertificate().build());
        assertThat(found).extracting(Disagreement::factor).containsExactly(Optional.of(Factor.MOISTURE));
    }

    @Test
    void theGradeLossIsPositiveWhenTheCertificateIsWorse() {
        assertThat(Inspection.gradeLoss(
                        house(NumericalGrade.US_1), aCertificate().build(), PRICES))
                .isEqualTo(d("0.0500"));
    }

    @Test
    void theGradeLossIsNegativeWhenTheCertificateIsBetter() {
        assertThat(Inspection.gradeLoss(
                        house(NumericalGrade.US_3), aCertificate().build(), PRICES))
                .isEqualTo(d("-0.1500"));
    }

    @Test
    void aGradeWithNoPostedPriceCannotBeCompared() {
        assertThatThrownBy(() -> Inspection.gradeLoss(
                        house(NumericalGrade.US_5), aCertificate().build(), PRICES))
                .isInstanceOf(InspectionError.class);
    }

    @Test
    void theWorstCertificateOfASet() {
        Certificate better =
                aCertificate().number("KS-1").grade(NumericalGrade.US_1).build();
        Certificate worse =
                aCertificate().number("KS-3").grade(NumericalGrade.US_3).build();
        assertThat(Inspection.worstOf(List.of(better, aCertificate().build(), worse))
                        .number())
                .isEqualTo("KS-3");
    }

    @Test
    void theWorstOfNothingIsRefused() {
        assertThatThrownBy(() -> Inspection.worstOf(List.of())).isInstanceOf(InspectionError.class);
    }
}
