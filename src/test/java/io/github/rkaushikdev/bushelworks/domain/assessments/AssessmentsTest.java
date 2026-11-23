package io.github.rkaushikdev.bushelworks.domain.assessments;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class AssessmentsTest {

    static Assessment usb() {
        return Assessment.of("USB", "United Soybean Board", "USDA", "SOYB", d("0.5"))
                .withBasis(AssessmentBasis.PERCENT_OF_VALUE);
    }

    static List<String> codes(List<Assessment> assessments) {
        return assessments.stream().map(Assessment::code).toList();
    }

    @Test
    void perBushelLevyExtendsOverBushels() {
        Assessment levy = Assessment.of("KSCORN", "Kansas Corn Commission", "KDA", "CORN", d("0.0100"))
                .withStateCode("KS");
        assertThat(levy.withhold(d("932.8571"), d("4057.93"), d("4000.00"))).isEqualTo(d("9.33"));
    }

    @Test
    void aSeedLevyIsTakenByTheHundredweight() {
        Assessment levy = Assessment.of("SUNCHK", "Sunflower Checkoff", "Oilseed Council", "SUNF", d("0.0150"))
                .withBasis(AssessmentBasis.PER_HUNDREDWEIGHT);
        // 1,744.40 bushels at 25 lb is 43,610 lb, or 436.10 hundredweight
        assertThat(levy.hundredweight(d("1744.4000"))).isEqualTo(d("436.10"));
        assertThat(levy.withhold(d("1744.4000"), d("9000.00"), d("8800.00"))).isEqualTo(d("6.54"));
        assertThat(AssessmentBasis.of("per_hundredweight")).isSameAs(AssessmentBasis.PER_HUNDREDWEIGHT);
    }

    @Test
    void theStandardSheetReadsAsTheLeviesTheHouseCollects() {
        AssessmentBook book = AssessmentBook.standard();
        assertThat(book.codes()).containsExactly("KSCORN", "KSWHEAT", "NCB", "NECORN", "USB");
        Assessment kansas = book.applicable("CORN", "KS").stream()
                .filter(one -> one.code().equals("KSCORN"))
                .findFirst()
                .orElseThrow();
        assertThat(kansas.refundable()).isTrue();
        assertThat(kansas.rate()).isEqualTo(d("0.0100"));
        assertThat(kansas.authority()).isEqualTo("Kansas Department of Agriculture");
        assertThat(codes(book.applicable("SOYB", "NE"))).containsExactly("USB");
        assertThat(book.applicable("SOYB", "KS").get(0).basis()).isSameAs(AssessmentBasis.PERCENT_OF_VALUE);
    }

    @Test
    void aSheetLineThatCannotBeReadIsRefused() {
        assertThatThrownBy(() -> AssessmentTable.parse("NCB | Corn | NCGA | CORN | * | per_bushel | 0.0000"))
                .isInstanceOf(AssessmentError.class)
                .hasMessageContaining("9 columns");
        assertThatThrownBy(() -> AssessmentTable.parse(
                        "NCB | Corn | NCGA | CORN | * | per_bushel | nil | net_of_discounts | no"))
                .isInstanceOf(AssessmentError.class)
                .hasMessageContaining("'nil' is not a number");
        assertThatThrownBy(() -> AssessmentTable.parse(
                        "NCB | Corn | NCGA | CORN | * | per_bushel | 0.01 | net_of_discounts | maybe"))
                .isInstanceOf(AssessmentError.class)
                .hasMessageContaining("yes or no");
        assertThat(AssessmentTable.parse("# nothing collected here")).isEmpty();
    }

    @Test
    void percentLevyUsesTheNetValueByDefault() {
        assertThat(usb().withhold(d("800"), d("9000.00"), d("8800.00"))).isEqualTo(d("44.00"));
    }

    @Test
    void percentLevyCanBeFiledAgainstTheGross() {
        Assessment levy = usb().withValueBasis(ValueBasis.GROSS);
        assertThat(levy.withhold(d("800"), d("9000.00"), d("8800.00"))).isEqualTo(d("45.00"));
    }

    @Test
    void aPercentLevyOnAWorthlessLoadWithholdsNothing() {
        assertThat(usb().withhold(d("800"), d("0"), d("0"))).isEqualTo(d("0.00"));
    }

    @Test
    void aNegativeRateIsRefused() {
        assertThatThrownBy(() -> Assessment.of("X", "X", "X", "CORN", d("-0.01")))
                .isInstanceOf(AssessmentError.class);
    }

    @Test
    void anImplausiblePercentageIsRefused() {
        assertThatThrownBy(
                        () -> Assessment.of("X", "X", "X", "SOYB", d("50")).withBasis(AssessmentBasis.PERCENT_OF_VALUE))
                .isInstanceOf(AssessmentError.class);
    }

    @Test
    void stateFilterPicksOnlyTheLeviesThatApply() {
        AssessmentBook book = AssessmentBook.standard();
        List<String> kansas = codes(book.applicable("CORN", "KS"));
        List<String> nebraska = codes(book.applicable("CORN", "NE"));
        assertThat(kansas).contains("KSCORN").doesNotContain("NECORN");
        assertThat(nebraska).contains("NECORN").doesNotContain("KSCORN");
    }

    @Test
    void aWildcardLevyAppliesEverywhere() {
        AssessmentBook book = AssessmentBook.standard();
        assertThat(codes(book.applicable("CORN", "KS"))).contains("NCB");
        assertThat(codes(book.applicable("CORN", "NE"))).contains("NCB");
    }

    @Test
    void aZeroRateLevyProducesNoLine() {
        List<WithheldLine> lines =
                AssessmentBook.standard().withhold("CORN", "KS", d("10000"), d("45000.00"), d("44000.00"));
        assertThat(lines).extracting(WithheldLine::code).containsExactly("KSCORN");
        assertThat(lines.get(0).dollars()).isEqualTo(d("100.00"));
    }

    @Test
    void exemptCodesAreSkipped() {
        List<WithheldLine> lines = AssessmentBook.standard()
                .withhold("CORN", "KS", d("10000"), d("45000.00"), d("44000.00"), List.of("KSCORN"));
        assertThat(lines).isEmpty();
    }

    @Test
    void totalsAndTheRefundablePortion() {
        AssessmentBook book = AssessmentBook.standard();
        List<WithheldLine> lines = book.withhold("CORN", "KS", d("10000"), d("45000.00"), d("44000.00"));
        assertThat(book.totalWithheld(lines)).isEqualTo(d("100.00"));
        assertThat(book.refundablePortion(lines)).isEqualTo(d("100.00"));
    }

    @Test
    void aNonRefundableLevyIsNotInTheRefundableTotal() {
        AssessmentBook book = AssessmentBook.standard();
        List<WithheldLine> lines = book.withhold("CORN", "NE", d("10000"), d("45000.00"), d("44000.00"));
        assertThat(book.totalWithheld(lines)).isEqualTo(d("50.00"));
        assertThat(book.refundablePortion(lines)).isEqualTo(d("0.00"));
    }

    @Test
    void theBookRefusesTheSameLevyTwice() {
        AssessmentBook book = new AssessmentBook();
        Assessment levy =
                Assessment.of("KSCORN", "Kansas Corn", "KDA", "CORN", d("0.01")).withStateCode("KS");
        book.add(levy);
        assertThatThrownBy(() -> book.add(levy)).isInstanceOf(AssessmentError.class);
    }

    @Test
    void theBookListsItsCodes() {
        assertThat(AssessmentBook.standard().codes()).containsExactly("KSCORN", "KSWHEAT", "NCB", "NECORN", "USB");
    }

    @Test
    void aCommodityWithNoLevyInThatStateGetsNothing() {
        assertThat(AssessmentBook.standard().applicable("HRW", "NE")).isEmpty();
    }
}
