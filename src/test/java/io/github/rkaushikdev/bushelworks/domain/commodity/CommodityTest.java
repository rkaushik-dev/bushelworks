package io.github.rkaushikdev.bushelworks.domain.commodity;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CommodityTest {

    @Test
    void masterHoldsTheFiveCommoditiesTheHouseBuys() {
        assertThat(Commodities.codes()).containsExactly("CORN", "HRW", "MILO", "SOYB", "SUNF");
    }

    @Test
    void cornIsFiftySixPoundBushelsAtFifteenPercent() {
        assertThat(Commodities.CORN.standardTestWeight()).isEqualByComparingTo(d("56"));
        assertThat(Commodities.CORN.baseMoisture()).isEqualByComparingTo(d("15.0"));
        assertThat(Commodities.CORN.priceUnit()).isSameAs(PriceUnit.BUSHEL);
    }

    @Test
    void sunflowerIsPricedByHundredweight() {
        assertThat(Commodities.SUNFLOWER.priceUnit()).isSameAs(PriceUnit.HUNDREDWEIGHT);
    }

    @Test
    void gradedFactorsIsRequiredPlusOptional() {
        assertThat(Commodities.CORN.gradedFactors()).contains(Factor.DAMAGE_HEAT);
        assertThat(Commodities.CORN.requiredFactors()).doesNotContain(Factor.DAMAGE_HEAT);
        assertThat(Commodities.CORN.accepts(Factor.BCFM)).isTrue();
        assertThat(Commodities.CORN.accepts(Factor.PROTEIN)).isFalse();
    }

    @Test
    void wheatGradesDockageAndCornDoesNot() {
        assertThat(Commodities.HRW_WHEAT.accepts(Factor.DOCKAGE)).isTrue();
        assertThat(Commodities.CORN.accepts(Factor.DOCKAGE)).isFalse();
    }

    @Test
    void unknownCommodityRaises() {
        assertThatThrownBy(() -> Commodities.get("BARLEY")).isInstanceOf(UnknownCommodity.class);
    }

    @Test
    void aFactorCannotBeBothRequiredAndOptional() {
        assertThatThrownBy(() -> new Commodity(
                        "X", "X", d("56"), d("15"), PriceUnit.BUSHEL, Set.of(Factor.MOISTURE), Set.of(Factor.MOISTURE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void baseMoistureMustBeAPercentage() {
        assertThatThrownBy(() -> new Commodity("X", "X", d("56"), d("150"), PriceUnit.BUSHEL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void factorDirectionCoversEveryFactor() {
        assertThat(Arrays.stream(Factor.values()).map(Factor::direction)).doesNotContainNull();
        assertThat(Factor.TEST_WEIGHT.direction()).isSameAs(FactorDirection.LOW_IS_BAD);
        assertThat(Factor.MOISTURE.direction()).isSameAs(FactorDirection.HIGH_IS_BAD);
    }
}
