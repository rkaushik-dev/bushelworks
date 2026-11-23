package io.github.rkaushikdev.bushelworks.domain.commodity;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CommodityTest {

    @Test
    void masterHoldsTheFiveCommoditiesTheHouseBuys() {
        assertThat(Commodities.codes()).containsExactly("CORN", "HRW", "MILO", "SOYB", "SUNF");
    }

    @Test
    void theMasterSheetReadsAsTheMaster() {
        List<Commodity> rows = Commodities.parse(Commodities.MASTER_SHEET);
        assertThat(rows).extracting(Commodity::code).containsExactly("CORN", "SOYB", "HRW", "MILO", "SUNF");
        for (Commodity row : rows) {
            assertThat(Commodities.get(row.code())).as(row.code()).isEqualTo(row);
        }
        assertThat(Commodities.HRW_WHEAT.requiredFactors())
                .containsExactlyInAnyOrder(
                        Factor.TEST_WEIGHT,
                        Factor.MOISTURE,
                        Factor.DOCKAGE,
                        Factor.DAMAGE_TOTAL,
                        Factor.SHRUNKEN_BROKEN,
                        Factor.TOTAL_DEFECTS);
        assertThat(Commodities.GRAIN_SORGHUM.optionalFactors()).doesNotContain(Factor.OIL, Factor.DAMAGE_HEAT);
    }

    @Test
    void aHouseAddsACommodityByAddingARow() {
        List<Commodity> rows = Commodities.parse(
                """
                # field peas for the pulse buyer
                code  | name       | lb/bu | base moisture | priced per | required                  | optional
                PEAS  | Field Peas | 60    | 14.0          | CWT        | moisture foreign_material | -
                """);
        Commodity peas = rows.get(0);
        assertThat(peas.name()).isEqualTo("Field Peas");
        assertThat(peas.standardTestWeight()).isEqualByComparingTo(d("60"));
        assertThat(peas.priceUnit()).isSameAs(PriceUnit.HUNDREDWEIGHT);
        assertThat(peas.requiredFactors()).containsExactlyInAnyOrder(Factor.MOISTURE, Factor.FOREIGN_MATERIAL);
        assertThat(peas.accepts(Factor.GLASS)).isTrue();
        assertThat(Commodities.codes()).doesNotContain("PEAS");
    }

    @Test
    void aMasterRowThatCannotBeReadIsRefused() {
        assertThatThrownBy(() -> Commodities.parse("PEAS | Field Peas | 60 | 14.0 | CWT | moisture"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("7 columns");
        assertThatThrownBy(
                        () -> Commodities.parse(
                                """
                        PEAS | Field Peas | 60 | 14.0 | CWT | moisture | -
                        PEAS | Field Peas | 60 | 14.0 | CWT | moisture | -
                        """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("twice");
        assertThatThrownBy(() -> Commodities.parse("PEAS | Field Peas | 60 | 14.0 | BAG | moisture | -"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Commodities.parse("PEAS | Field Peas | 60 | 14.0 | CWT | moisture | colour"))
                .isInstanceOf(IllegalArgumentException.class);
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

    @Test
    void aReadingAtTheLimitIsNotPastIt() {
        assertThat(Factor.TEST_WEIGHT.isPast(d("54.0"), d("53.9"))).isTrue();
        assertThat(Factor.TEST_WEIGHT.isPast(d("54.0"), d("54.0"))).isFalse();
        assertThat(Factor.TEST_WEIGHT.isPast(d("54.0"), d("58.2"))).isFalse();
        assertThat(Factor.BCFM.isPast(d("3.0"), d("3.1"))).isTrue();
        assertThat(Factor.BCFM.isPast(d("3.0"), d("3.0"))).isFalse();
        assertThat(Factor.STONES.direction()).isSameAs(FactorDirection.HIGH_IS_BAD);
    }
}
