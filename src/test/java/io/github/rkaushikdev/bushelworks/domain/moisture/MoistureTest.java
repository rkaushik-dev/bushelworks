package io.github.rkaushikdev.bushelworks.domain.moisture;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class MoistureTest {

    static final ShrinkSchedule CORN_TABLE =
            ShrinkSchedule.of("CORN", d("15.0")).withMethod(ShrinkMethod.TABLE).withShrinkFactor(d("1.18"));

    static final ShrinkSchedule CORN_WATER =
            ShrinkSchedule.of("CORN", d("15.0")).withMethod(ShrinkMethod.WATER).withHandlingLossPercent(d("0.5"));

    static final DryingSchedule DRYER = DryingSchedule.of(
            "CORN",
            List.of(
                    DryingTier.upTo(d("2"), d("0.0300")),
                    DryingTier.upTo(d("5"), d("0.0450")),
                    DryingTier.open(d("0.0600"))));

    @Test
    void dryGrainDoesNotShrink() {
        assertThat(CORN_TABLE.pointsOver(d("12.4"))).isEqualByComparingTo(d("0"));
        assertThat(CORN_TABLE.shrinkPercent(d("12.4"))).isEqualTo(d("0.0000"));
    }

    @Test
    void atBaseMoistureThereIsNoShrink() {
        assertThat(CORN_TABLE.shrinkPercent(d("15.0"))).isEqualTo(d("0.0000"));
    }

    @Test
    void tableShrinkIsPointsTimesFactor() {
        // 3.2 points over base at 1.18 per point
        assertThat(CORN_TABLE.shrinkPercent(d("18.2"))).isEqualTo(d("3.7760"));
    }

    @Test
    void bareWaterShrinkIsSmallerThanTheTableAtTheSameMoisture() {
        ShrinkSchedule bare = ShrinkSchedule.of("CORN", d("15.0")).withMethod(ShrinkMethod.WATER);
        BigDecimal table = CORN_TABLE.shrinkPercent(d("18.2"));
        BigDecimal water = bare.shrinkPercent(d("18.2"));
        assertThat(water).isEqualTo(d("3.7647"));
        assertThat(water).isLessThan(table);
    }

    @Test
    void waterShrinkIncludesTheHandlingLoss() {
        // 3.2 / 85 * 100 = 3.7647, plus 0.5 handling
        assertThat(CORN_WATER.shrinkPercent(d("18.2"))).isEqualTo(d("4.2647"));
    }

    @Test
    void noneMethodNeverShrinks() {
        ShrinkSchedule schedule = ShrinkSchedule.of("SOYB", d("13.0")).withMethod(ShrinkMethod.NONE);
        assertThat(schedule.shrinkPercent(d("18.0"))).isEqualTo(d("0.0000"));
    }

    @Test
    void dryAndShrunkBushelsAddBackToWet() {
        BigDecimal wet = d("1000.0000");
        BigDecimal dry = CORN_TABLE.dryBushels(wet, d("18.2"));
        BigDecimal gone = CORN_TABLE.shrunkBushels(wet, d("18.2"));
        assertThat(dry).isEqualTo(d("962.2400"));
        assertThat(dry.add(gone)).isEqualTo(wet);
    }

    @Test
    void moistureAboveTheHouseLimitIsRefused() {
        ShrinkSchedule schedule = ShrinkSchedule.of("CORN", d("15.0")).withRejectAbove(d("25.0"));
        assertThatThrownBy(() -> schedule.shrinkPercent(d("26.4"))).isInstanceOf(MoistureError.class);
    }

    @Test
    void nonsenseMoistureIsRefused() {
        assertThatThrownBy(() -> CORN_TABLE.shrinkPercent(d("-1"))).isInstanceOf(MoistureError.class);
        assertThatThrownBy(() -> CORN_TABLE.shrinkPercent(d("100"))).isInstanceOf(MoistureError.class);
    }

    static final ShrinkChart CORN_CHART = ShrinkChart.of(List.of(
            new ShrinkChart.Row(d("15.5"), d("0.6")),
            new ShrinkChart.Row(d("16.0"), d("1.2")),
            new ShrinkChart.Row(d("16.5"), d("1.8")),
            new ShrinkChart.Row(d("17.0"), d("2.4")),
            new ShrinkChart.Row(d("17.5"), d("3.0")),
            new ShrinkChart.Row(d("18.0"), d("3.6")),
            new ShrinkChart.Row(d("18.5"), d("4.3"))));

    static final ShrinkSchedule CORN_CHARTED =
            ShrinkSchedule.of("CORN", d("15.0")).withChart(CORN_CHART);

    @Test
    void aChartedReadingTakesTheRowItRunsTo() {
        assertThat(CORN_CHARTED.method()).isEqualTo(ShrinkMethod.CHART);
        assertThat(CORN_CHARTED.shrinkPercent(d("17.5"))).isEqualTo(d("3.0000"));
        assertThat(CORN_CHARTED.shrinkPercent(d("17.3"))).isEqualTo(d("3.0000"));
        assertThat(CORN_CHARTED.shrinkPercent(d("17.6"))).isEqualTo(d("3.6000"));
        assertThat(CORN_CHARTED.shrinkPercent(d("15.1"))).isEqualTo(d("0.6000"));
    }

    @Test
    void aChartedLoadAtOrUnderBaseDoesNotShrink() {
        assertThat(CORN_CHARTED.shrinkPercent(d("15.0"))).isEqualTo(d("0.0000"));
        assertThat(CORN_CHARTED.shrinkPercent(d("13.8"))).isEqualTo(d("0.0000"));
    }

    @Test
    void theChartAndTheFactorDisagreeOnTheWorkedLoad() {
        // the chart's 18.5 row is 4.3, the factor gives 3.2 points at 1.18
        assertThat(CORN_CHARTED.shrinkPercent(d("18.2"))).isEqualTo(d("4.3000"));
        assertThat(CORN_TABLE.shrinkPercent(d("18.2"))).isEqualTo(d("3.7760"));
        assertThat(CORN_CHARTED.dryBushels(d("1000.0000"), d("18.2"))).isEqualTo(d("957.0000"));
    }

    @Test
    void aReadingOffTheEndOfAChartThatStopsIsRefused() {
        assertThatThrownBy(() -> CORN_CHARTED.shrinkPercent(d("19.0")))
                .isInstanceOf(MoistureError.class)
                .hasMessageContaining("18.5");
    }

    @Test
    void aChartThatRunsOnAddsSoMuchAPointPastItsLastRow() {
        ShrinkSchedule runsOn = ShrinkSchedule.of("CORN", d("15.0")).withChart(CORN_CHART.runningOnAt(d("1.4")));
        assertThat(runsOn.shrinkPercent(d("19.5"))).isEqualTo(d("5.7000"));
        assertThat(runsOn.shrinkPercent(d("18.5"))).isEqualTo(d("4.3000"));
    }

    @Test
    void theHouseLimitStillAppliesToAChartedLoad() {
        ShrinkSchedule limited = CORN_CHARTED.withRejectAbove(d("18.0"));
        assertThat(limited.chart()).contains(CORN_CHART);
        assertThatThrownBy(() -> limited.shrinkPercent(d("18.2"))).isInstanceOf(MoistureError.class);
    }

    @Test
    void theChartMethodNeedsAChart() {
        assertThatThrownBy(() -> ShrinkSchedule.of("CORN", d("15.0")).withMethod(ShrinkMethod.CHART))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ShrinkMethod.of("chart")).isEqualTo(ShrinkMethod.CHART);
    }

    @Test
    void aChartHasToReadDownTheWayItIsPrinted() {
        assertThatThrownBy(() -> ShrinkChart.of(List.of())).isInstanceOf(MoistureError.class);
        assertThatThrownBy(() -> ShrinkChart.of(
                        List.of(new ShrinkChart.Row(d("16.0"), d("1.2")), new ShrinkChart.Row(d("15.5"), d("0.6")))))
                .isInstanceOf(MoistureError.class);
        assertThatThrownBy(() -> ShrinkChart.of(
                        List.of(new ShrinkChart.Row(d("15.5"), d("1.2")), new ShrinkChart.Row(d("16.0"), d("0.6")))))
                .isInstanceOf(MoistureError.class);
        assertThatThrownBy(() -> new ShrinkChart.Row(d("15.5"), d("-0.1"))).isInstanceOf(MoistureError.class);
        assertThatThrownBy(() -> CORN_CHART.runningOnAt(d("0"))).isInstanceOf(MoistureError.class);
    }

    @Test
    void dryingWithinTheFirstTier() {
        assertThat(DRYER.rateFor(d("1.5"))).isEqualTo(d("0.0450"));
    }

    @Test
    void dryingSpansTiersCumulatively() {
        // 2 points at 0.03 plus 2 points at 0.045
        assertThat(DRYER.rateFor(d("4"))).isEqualTo(d("0.1500"));
    }

    @Test
    void dryingUsesTheOpenTierPastTheLastCeiling() {
        // 2 at 0.03, 3 at 0.045, 1 at 0.06
        assertThat(DRYER.rateFor(d("6"))).isEqualTo(d("0.2550"));
    }

    @Test
    void freePointsComeOffTheBottom() {
        DryingSchedule schedule =
                DryingSchedule.of("CORN", List.of(DryingTier.open(d("0.0500")))).withFreePoints(d("1.0"));
        assertThat(schedule.rateFor(d("1.0"))).isEqualTo(d("0.0000"));
        assertThat(schedule.rateFor(d("3.0"))).isEqualTo(d("0.1000"));
    }

    @Test
    void dryingChargeExtendsOverBushels() {
        assertThat(DRYER.charge(d("932.8571"), d("4"))).isEqualTo(d("139.93"));
    }

    @Test
    void minimumChargeAppliesOnlyWhenSomethingIsOwed() {
        DryingSchedule schedule =
                DryingSchedule.of("CORN", List.of(DryingTier.open(d("0.0500")))).withMinimumCharge(d("25.00"));
        assertThat(schedule.charge(d("100"), d("0"))).isEqualTo(d("0.00"));
        assertThat(schedule.charge(d("100"), d("1"))).isEqualTo(d("25.00"));
    }

    @Test
    void tiersMustBeInOrder() {
        assertThatThrownBy(() -> DryingSchedule.of(
                        "CORN", List.of(DryingTier.upTo(d("5"), d("0.03")), DryingTier.upTo(d("2"), d("0.05")))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nothingMayFollowTheOpenTier() {
        assertThatThrownBy(() -> DryingSchedule.of(
                        "CORN", List.of(DryingTier.open(d("0.03")), DryingTier.upTo(d("9"), d("0.05")))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aScheduleNeedsAtLeastOneTier() {
        assertThatThrownBy(() -> DryingSchedule.of("CORN", List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dryingBasisDefaultsToWetBushels() {
        assertThat(DRYER.basis()).isSameAs(DryingBasis.WET);
    }
}
