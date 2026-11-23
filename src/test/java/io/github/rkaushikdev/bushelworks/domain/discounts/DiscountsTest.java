package io.github.rkaushikdev.bushelworks.domain.discounts;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DiscountsTest {

    /** Below 54 lb: a cent per half pound to 52, then two cents per half pound. */
    static final DiscountSchedule CORN_TEST_WEIGHT = new DiscountSchedule(
            "CORN",
            Factor.TEST_WEIGHT,
            List.of(
                    DiscountBand.of(d("54.0"), d("0.0100")).endingAt(d("52.0")).withIncrement(d("0.5")),
                    DiscountBand.of(d("52.0"), d("0.0200")).withIncrement(d("0.5"))),
            "light test weight");

    /** Over 3% BCFM: two cents per tenth of a point, prorated. */
    static final DiscountSchedule CORN_BCFM = new DiscountSchedule(
            "CORN",
            Factor.BCFM,
            List.of(DiscountBand.of(d("3.0"), d("0.0020"))
                    .withIncrement(d("0.1"))
                    .withMode(DiscountMode.PRORATE)));

    @Test
    void aBoardLineIsABandAndConsecutiveLinesAreOneSchedule() {
        List<DiscountSchedule> schedules = DiscountTable.parse(
                """
                commodity  factor       start  end   rate    per  mode     label
                # the corn board
                CORN       test_weight  54.0   52.0  0.0100  0.5  step     light test weight
                CORN       test_weight  52.0   -     0.0200  0.5  step     light test weight

                CORN       bcfm         3.0    -     0.0020  0.1  prorate
                """);
        assertThat(schedules).hasSize(2);
        assertThat(schedules.get(0)).isEqualTo(CORN_TEST_WEIGHT);
        assertThat(schedules.get(1)).isEqualTo(CORN_BCFM);
        assertThat(schedules.get(1).label()).isEmpty();
    }

    @Test
    void theSameFactorLaterOnTheBoardIsASecondScheduleAndTheBookRefusesIt() {
        List<DiscountSchedule> schedules = DiscountTable.parse(
                """
                CORN  bcfm          3.0  -  0.0020  0.1  prorate  bcfm
                CORN  damage_total  5.0  -  0.0100  1.0  step     total damage
                CORN  bcfm          4.0  -  0.0030  0.1  prorate  bcfm
                """);
        assertThat(schedules)
                .extracting(DiscountSchedule::factor)
                .containsExactly(Factor.BCFM, Factor.DAMAGE_TOTAL, Factor.BCFM);
        assertThatThrownBy(() -> new DiscountBook(schedules)).isInstanceOf(ScheduleError.class);
    }

    @Test
    void halfALineEditedIsCaughtByItsLabel() {
        assertThatThrownBy(
                        () -> DiscountTable.parse(
                                """
                        CORN  test_weight  54.0  52.0  0.0100  0.5  step  light test weight
                        CORN  test_weight  52.0  -     0.0200  0.5  step  light corn
                        """))
                .isInstanceOf(ScheduleError.class)
                .hasMessageContaining("light corn");
    }

    @Test
    void aBoardLineThatCannotBeReadIsRefused() {
        assertThatThrownBy(() -> DiscountTable.parse("CORN  bcfm  3.0  -  0.0020  0.1"))
                .isInstanceOf(ScheduleError.class);
        assertThatThrownBy(() -> DiscountTable.parse("CORN  bcfm  three  -  0.0020  0.1  prorate"))
                .isInstanceOf(ScheduleError.class)
                .hasMessageContaining("'three' is not a number");
        assertThatThrownBy(() -> DiscountTable.parse("CORN  bcfm  3.0  -  0.0020  0.1  sometimes"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DiscountTable.parse("CORN  bcfm  3.0  2.0  0.0020  0.1  step"))
                .isInstanceOf(ScheduleError.class);
        assertThat(DiscountTable.parse("# nothing filed yet")).isEmpty();
    }

    @Test
    void atOrAboveTheThresholdCostsNothing() {
        assertThat(CORN_TEST_WEIGHT.rateFor(d("56.0"))).isEqualTo(d("0.0000"));
        assertThat(CORN_TEST_WEIGHT.rateFor(d("54.0"))).isEqualTo(d("0.0000"));
    }

    @Test
    void aPartialStepIsChargedAsAWholeOne() {
        // 0.1 lb under 54 still buys a full half pound step
        assertThat(CORN_TEST_WEIGHT.rateFor(d("53.9"))).isEqualTo(d("0.0100"));
        assertThat(CORN_TEST_WEIGHT.rateFor(d("53.5"))).isEqualTo(d("0.0100"));
        assertThat(CORN_TEST_WEIGHT.rateFor(d("53.4"))).isEqualTo(d("0.0200"));
    }

    @Test
    void bandsStackRatherThanReplace() {
        // 2 lb in the first band at 0.01 per half pound is 0.04,
        // then 1 lb in the second at 0.02 per half pound is 0.04
        assertThat(CORN_TEST_WEIGHT.rateFor(d("51.0"))).isEqualTo(d("0.0800"));
    }

    @Test
    void theFirstBandIsFullyConsumedBeforeTheSecondStarts() {
        assertThat(CORN_TEST_WEIGHT.rateFor(d("52.0"))).isEqualTo(d("0.0400"));
    }

    @Test
    void prorateChargesTheExactFraction() {
        // 0.35 over 3.0 is 3.5 increments at 0.002
        assertThat(CORN_BCFM.rateFor(d("3.35"))).isEqualTo(d("0.0070"));
    }

    @Test
    void flatModeChargesOnceHoweverFarOver() {
        DiscountSchedule schedule = new DiscountSchedule(
                "SOYB",
                Factor.FOREIGN_MATERIAL,
                List.of(DiscountBand.of(d("2.0"), d("0.0500")).withMode(DiscountMode.FLAT)));
        assertThat(schedule.rateFor(d("2.1"))).isEqualTo(d("0.0500"));
        assertThat(schedule.rateFor(d("9.9"))).isEqualTo(d("0.0500"));
        assertThat(schedule.rateFor(d("2.0"))).isEqualTo(d("0.0000"));
    }

    @Test
    void excessIsMeasuredTowardTheBadEnd() {
        assertThat(CORN_TEST_WEIGHT.excess(d("51.5"))).isEqualByComparingTo(d("2.5"));
        assertThat(CORN_BCFM.excess(d("4.2"))).isEqualByComparingTo(d("1.2"));
        assertThat(CORN_BCFM.excess(d("1.0"))).isEqualByComparingTo(d("0"));
    }

    @Test
    void chargeExtendsTheRateOverBushels() {
        assertThat(CORN_TEST_WEIGHT.charge(d("932.8571"), d("51.0"))).isEqualTo(d("74.63"));
    }

    @Test
    void aScheduleNeedsBands() {
        assertThatThrownBy(() -> new DiscountSchedule("CORN", Factor.BCFM, List.of()))
                .isInstanceOf(ScheduleError.class);
    }

    @Test
    void bandsMayNotLeaveAGap() {
        assertThatThrownBy(() -> new DiscountSchedule(
                        "CORN",
                        Factor.TEST_WEIGHT,
                        List.of(
                                DiscountBand.of(d("54"), d("0.01")).endingAt(d("52")),
                                DiscountBand.of(d("50"), d("0.02")))))
                .isInstanceOf(ScheduleError.class);
    }

    @Test
    void nothingMayFollowAnOpenBand() {
        assertThatThrownBy(() -> new DiscountSchedule(
                        "CORN",
                        Factor.TEST_WEIGHT,
                        List.of(DiscountBand.of(d("54"), d("0.01")), DiscountBand.of(d("52"), d("0.02")))))
                .isInstanceOf(ScheduleError.class);
    }

    @Test
    void aLowIsBadBandHasToDescend() {
        assertThatThrownBy(() -> new DiscountSchedule(
                        "CORN",
                        Factor.TEST_WEIGHT,
                        List.of(DiscountBand.of(d("54"), d("0.01")).endingAt(d("56")))))
                .isInstanceOf(ScheduleError.class);
    }

    @Test
    void incrementMustBePositive() {
        assertThatThrownBy(() -> DiscountBand.of(d("54"), d("0.01")).withIncrement(d("0")))
                .isInstanceOf(ScheduleError.class);
    }

    @Test
    void bookAddsEveryApplicableDiscount() {
        DiscountBook book = new DiscountBook(List.of(CORN_TEST_WEIGHT, CORN_BCFM));
        Map<Factor, BigDecimal> sample = new LinkedHashMap<>();
        sample.put(Factor.TEST_WEIGHT, d("53.4"));
        sample.put(Factor.BCFM, d("3.35"));
        sample.put(Factor.MOISTURE, d("18.2"));
        assertThat(book.totalRate("CORN", sample)).isEqualTo(d("0.0270"));
    }

    @Test
    void bookBreakdownSkipsTheFactorsThatCostNothing() {
        DiscountBook book = new DiscountBook(List.of(CORN_TEST_WEIGHT, CORN_BCFM));
        Map<Factor, BigDecimal> sample = new LinkedHashMap<>();
        sample.put(Factor.TEST_WEIGHT, d("56.0"));
        sample.put(Factor.BCFM, d("3.35"));
        assertThat(book.breakdown("CORN", sample)).isEqualTo(Map.of(Factor.BCFM, d("0.0070")));
    }

    @Test
    void bookRefusesTwoSchedulesForOneFactor() {
        DiscountBook book = new DiscountBook(List.of(CORN_BCFM));
        assertThatThrownBy(() -> book.add(CORN_BCFM)).isInstanceOf(ScheduleError.class);
    }

    @Test
    void bookListsTheFactorsItCovers() {
        DiscountBook book = new DiscountBook(List.of(CORN_TEST_WEIGHT, CORN_BCFM));
        assertThat(book.factors("CORN")).containsExactly(Factor.BCFM, Factor.TEST_WEIGHT);
        assertThat(book.factors("SOYB")).isEmpty();
        assertThat(book.forFactor("SOYB", Factor.BCFM)).isEmpty();
    }
}
