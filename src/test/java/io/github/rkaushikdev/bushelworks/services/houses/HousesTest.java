package io.github.rkaushikdev.bushelworks.services.houses;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static io.github.rkaushikdev.bushelworks.services.houses.Houses.discountBook;
import static io.github.rkaushikdev.bushelworks.services.houses.Houses.houseRulebook;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.bushelworks.Settings;
import io.github.rkaushikdev.bushelworks.domain.commodity.Factor;
import io.github.rkaushikdev.bushelworks.domain.discounts.DiscountBook;
import io.github.rkaushikdev.bushelworks.domain.discounts.DiscountSchedule;
import io.github.rkaushikdev.bushelworks.domain.moisture.ShrinkMethod;
import io.github.rkaushikdev.bushelworks.domain.moisture.ShrinkSchedule;
import io.github.rkaushikdev.bushelworks.domain.rulebook.RuleBook;
import io.github.rkaushikdev.bushelworks.schedules.Version;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class HousesTest {

    static final LocalDate OCTOBER = LocalDate.of(2024, 10, 3);

    @Test
    void theHouseFilesBothCropYearsOfCornShrink() {
        RuleBook book = houseRulebook();
        assertThat(book.shrinkFor("CORN", LocalDate.of(2024, 7, 1)).shrinkFactor())
                .isEqualTo(d("1.20"));
        assertThat(book.shrinkFor("CORN", OCTOBER).shrinkFactor()).isEqualTo(d("1.18"));
    }

    @Test
    void theOlderTableIsClosedWhenTheNewerOneStarts() {
        List<Version<ShrinkSchedule>> history = houseRulebook().shrink().history("CORN");
        assertThat(history.get(0).effectiveTo()).contains(LocalDate.of(2024, 7, 31));
    }

    @Test
    void wheatIsFiledWithNoShrink() {
        assertThat(houseRulebook().shrinkFor("HRW", OCTOBER).method()).isSameAs(ShrinkMethod.NONE);
    }

    @Test
    void onlyCornHasADryerOnFile() {
        RuleBook book = houseRulebook();
        assertThat(book.dryingFor("CORN", OCTOBER)).isPresent();
        assertThat(book.dryingFor("SOYB", OCTOBER)).isEmpty();
    }

    @Test
    void allThreeCommoditiesHaveAStorageRate() {
        RuleBook book = houseRulebook();
        for (String code : List.of("CORN", "SOYB", "HRW")) {
            assertThat(book.storageFor(code, OCTOBER)).as(code).isPresent();
        }
    }

    @Test
    void theDiscountBookCoversTheFactorsTheBoardShows() {
        DiscountBook book = discountBook();
        assertThat(book.factors("CORN")).containsExactly(Factor.BCFM, Factor.DAMAGE_TOTAL, Factor.TEST_WEIGHT);
        assertThat(book.factors("HRW")).containsExactly(Factor.DOCKAGE, Factor.TEST_WEIGHT);
    }

    @Test
    void theBoardReadsAsSevenSchedulesOfEightBands() {
        DiscountBook book = discountBook();
        assertThat(book.schedules()).hasSize(7);
        assertThat(book.schedules().stream().mapToInt(one -> one.bands().size()).sum())
                .isEqualTo(8);
        DiscountSchedule light = book.forFactor("CORN", Factor.TEST_WEIGHT).orElseThrow();
        assertThat(light.label()).isEqualTo("light test weight");
        // four half pounds at a cent down to 52, one at two cents below it
        assertThat(light.rateFor(new BigDecimal("51.5"))).isEqualTo(new BigDecimal("0.0600"));
    }

    @Test
    void thePolicyTakesTheStateFromSettings() {
        RuleBook book = houseRulebook(Settings.builder().stateCode("NE").build());
        assertThat(book.policy().stateCode()).isEqualTo("NE");
    }

    @Test
    void thePolicyTakesTheMinimumFromSettings() {
        RuleBook book =
                houseRulebook(Settings.builder().minimumSettlement(d("25.00")).build());
        assertThat(book.policy().minimumSettlement()).isEqualTo(d("25.00"));
    }

    @Test
    void wheatDockageIsProratedRatherThanStepped() {
        DiscountSchedule schedule =
                discountBook().forFactor("HRW", Factor.DOCKAGE).orElseThrow();
        assertThat(schedule.rateFor(d("1.25"))).isEqualTo(d("0.0050"));
    }

    @Test
    void lightWheatIsChargedByThePound() {
        DiscountSchedule schedule =
                discountBook().forFactor("HRW", Factor.TEST_WEIGHT).orElseThrow();
        assertThat(schedule.rateFor(d("57.5"))).isEqualTo(d("0.0200"));
        assertThat(schedule.rateFor(d("56.0"))).isEqualTo(d("0.0400"));
    }

    @Test
    void theHouseCoversTheThreeCommoditiesItBuys() {
        RuleBook book = houseRulebook();
        for (String code : List.of("CORN", "SOYB", "HRW")) {
            assertThat(book.covers(code, OCTOBER, "HOME")).as(code).isTrue();
        }
        assertThat(book.covers("MILO", OCTOBER, "HOME")).isFalse();
    }

    @Test
    void nothingIsCoveredBeforeTheFirstCropYear() {
        assertThat(houseRulebook().covers("CORN", LocalDate.of(2023, 1, 1), "HOME"))
                .isFalse();
    }
}
