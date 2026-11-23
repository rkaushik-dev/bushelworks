package io.github.rkaushikdev.bushelworks.domain.dryer;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class DryerTest {

    static final LocalDateTime STARTED = LocalDateTime.of(2024, 10, 3, 6, 0);
    static final BigDecimal RATE = d("0.0450");
    static final BigDecimal PROPANE = d("1.85");
    static final BigDecimal POWER = d("0.11");
    static final LocalDate AUTUMN_OPENS = LocalDate.of(2024, 9, 1);
    static final LocalDate AUTUMN_CLOSES = LocalDate.of(2024, 12, 31);

    static Run.Builder aRun() {
        return Run.builder()
                .runId("D-0114")
                .locationCode("HOME")
                .commodityCode("CORN")
                .startedAt(STARTED)
                .endedAt(STARTED.plusHours(8))
                .bushels(d("20000"))
                .pointsRemoved(d("3.2"))
                .propaneGallons(d("1280"))
                .kwh(d("768"))
                .operator("dkoehn");
    }

    static Run idle(String runId) {
        return aRun().runId(runId).bushels(d("0")).pointsRemoved(d("0")).build();
    }

    static Season autumn(List<Run> runs) {
        return Season.of(runs, AUTUMN_OPENS, AUTUMN_CLOSES, RATE, PROPANE, POWER);
    }

    @Test
    void aRunNeedsAnIdentifier() {
        assertThatThrownBy(() -> aRun().runId("").build()).isInstanceOf(DryerError.class);
    }

    @Test
    void aRunCannotEndBeforeItStarts() {
        assertThatThrownBy(() -> aRun().endedAt(STARTED.minusHours(1)).build()).isInstanceOf(DryerError.class);
    }

    @Test
    void negativeQuantitiesAreRefused() {
        assertThatThrownBy(() -> aRun().propaneGallons(d("-1")).build()).isInstanceOf(DryerError.class);
    }

    @Test
    void theHoursComeOffTheClock() {
        assertThat(aRun().build().hours()).isEqualTo(d("8.00"));
    }

    @Test
    void thePointBushelsAreWhatEverythingIsChargedOn() {
        assertThat(aRun().build().pointBushels()).isEqualTo(d("64000.00"));
    }

    @Test
    void theRevenueIsThePublishedRateOverThePointBushels() {
        assertThat(aRun().build().revenue(RATE)).isEqualTo(d("2880.00"));
    }

    @Test
    void theCostIsPropanePlusPower() {
        assertThat(aRun().build().cost(PROPANE, POWER)).isEqualTo(d("2452.48"));
    }

    @Test
    void theMarginIsTheDifference() {
        assertThat(aRun().build().margin(RATE, PROPANE, POWER)).isEqualTo(d("427.52"));
    }

    @Test
    void aRunThatDriedNothingIsIdle() {
        Run run = idle("D-0114");
        assertThat(run.isIdle()).isTrue();
        assertThat(run.revenue(RATE)).isEqualTo(d("0.00"));
        assertThat(run.cost(PROPANE, POWER)).isPositive();
    }

    @Test
    void anIdleRunHasNoFuelRate() {
        Run run = idle("D-0114");
        assertThat(run.gallonsPerPointBushel()).isEmpty();
        assertThat(run.isThirsty()).isFalse();
    }

    @Test
    void theFuelRateComparesWithTheRuleOfThumb() {
        assertThat(aRun().build().gallonsPerPointBushel()).contains(d("0.0200"));
        // 20,000 bushels at 56 pounds, 3.2 points over the 85 percent left at base
        assertThat(aRun().build().waterRemovedPounds()).isEqualTo(d("42165"));
        assertThat(aRun().build().expectedGallons(Run.STANDARD_BTU_PER_POUND_OF_WATER))
                .isEqualTo(d("1281"));
        assertThat(aRun().build().isThirsty()).isFalse();
    }

    @Test
    void aThirstyRunIsFlagged() {
        assertThat(aRun().propaneGallons(d("2000")).build().isThirsty()).isTrue();
        assertThat(aRun().propaneGallons(d("1601")).build().isThirsty()).isFalse();
        assertThat(aRun().propaneGallons(d("1602")).build().isThirsty()).isTrue();
    }

    @Test
    void aSoybeanRunDoesMoreWorkAPointThanACornRun() {
        Run beans = aRun().commodityCode("SOYB").propaneGallons(d("1650")).build();
        Run corn = aRun().propaneGallons(d("1650")).build();
        assertThat(beans.waterRemovedPounds()).isEqualTo(d("44138"));
        assertThat(beans.isThirsty()).isFalse();
        assertThat(corn.isThirsty()).isTrue();
    }

    @Test
    void aRunIsJudgedAgainstTheDryerItWentThrough() {
        Run run = aRun().build();
        assertThat(run.btuPerPoundOfWater()).contains(d("2778"));
        assertThat(run.isThirsty(d("2000"))).isTrue();
        assertThat(run.isThirsty(d("2780"))).isFalse();
        assertThatThrownBy(() -> run.expectedGallons(d("0"))).isInstanceOf(DryerError.class);
        assertThat(idle("D-0120").btuPerPoundOfWater()).isEmpty();
        assertThat(idle("D-0120").isThirsty(d("2000"))).isFalse();
    }

    @Test
    void aSeasonSaysWhatAPoundOfWaterCostItInHeat() {
        Season season = autumn(List.of(
                aRun().build(), aRun().runId("D-0115").propaneGallons(d("1500")).build()));
        assertThat(season.waterRemovedPounds()).isEqualTo(d("84330"));
        assertThat(season.btuPerPoundOfWater()).contains(d("3016"));
        assertThat(season.thirstyRuns(d("2000"))).extracting(Run::runId).containsExactly("D-0114", "D-0115");
        assertThat(autumn(List.of(idle("D-0120"))).btuPerPoundOfWater()).isEmpty();
    }

    @Test
    void aSeasonGathersTheRunsInsideIt() {
        LocalDateTime spring = LocalDateTime.of(2025, 3, 1, 6, 0);
        List<Run> runs = List.of(
                aRun().build(),
                aRun().runId("D-0115")
                        .startedAt(spring)
                        .endedAt(spring.plusHours(6))
                        .build());
        assertThat(autumn(runs).runs()).hasSize(1);
    }

    @Test
    void aSeasonThatClosesFirstIsRefused() {
        assertThatThrownBy(() -> Season.of(List.of(), AUTUMN_CLOSES, AUTUMN_OPENS, RATE, PROPANE, POWER))
                .isInstanceOf(DryerError.class);
    }

    @Test
    void theSeasonTotalsTheBushelsAndTheHours() {
        List<Run> runs = List.of(
                aRun().build(),
                aRun().runId("D-0115")
                        .startedAt(STARTED.plusDays(1))
                        .endedAt(STARTED.plusDays(1).plusHours(6))
                        .build());
        Season season = autumn(runs);
        assertThat(season.bushels()).isEqualTo(d("40000.0000"));
        assertThat(season.hours()).isEqualTo(d("14.00"));
    }

    @Test
    void theSeasonMarginIsRevenueLessCost() {
        Season season = autumn(List.of(aRun().build()));
        assertThat(season.margin()).isEqualByComparingTo(season.revenue().subtract(season.cost()));
    }

    @Test
    void theIdleRunsArePickedOut() {
        Season season = autumn(List.of(aRun().build(), idle("D-0116")));
        assertThat(season.idleRuns()).extracting(Run::runId).containsExactly("D-0116");
    }

    @Test
    void theThirstyRunsArePickedOut() {
        Run thirsty = aRun().runId("D-0117").propaneGallons(d("2000")).build();
        Season season = autumn(List.of(aRun().build(), thirsty));
        assertThat(season.thirstyRuns()).extracting(Run::runId).containsExactly("D-0117");
    }

    @Test
    void theBreakEvenRateIsWhatTheHouseWouldHaveToCharge() {
        Season season = autumn(List.of(aRun().build()));
        assertThat(season.breakEvenRate()).contains(d("0.0383"));
        assertThat(season.breakEvenRate().orElseThrow()).isLessThan(RATE);
    }

    @Test
    void aSeasonThatDriedNothingHasNoBreakEven() {
        Season season = autumn(List.of());
        assertThat(season.breakEvenRate()).isEmpty();
        assertThat(season.margin()).isEqualTo(d("0.00"));
    }

    @Test
    void aWetYearAtALowRateLosesMoney() {
        Season season = Season.of(List.of(aRun().build()), AUTUMN_OPENS, AUTUMN_CLOSES, d("0.0300"), PROPANE, POWER);
        assertThat(season.margin()).isNegative();
    }
}
