package io.github.rkaushikdev.bushelworks;

import static io.github.rkaushikdev.bushelworks.TestDecimals.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class UnitsTest {

    @Test
    void toPoundsRoundsHalfUp() {
        assertThat(Units.toPounds("52340.5")).isEqualTo(d("52341"));
        assertThat(Units.toPounds("52340.4")).isEqualTo(d("52340"));
    }

    @Test
    void toPoundsRejectsNegative() {
        assertThatThrownBy(() -> Units.toPounds("-1")).isInstanceOf(UnitError.class);
    }

    @Test
    void netPoundsSubtracts() {
        assertThat(Units.netPounds(78420, 26180)).isEqualTo(d("52240"));
    }

    @Test
    void netPoundsRejectsTareAboveGross() {
        assertThatThrownBy(() -> Units.netPounds(26180, 78420)).isInstanceOf(UnitError.class);
    }

    @Test
    void cornBushelsAtStandardTestWeight() {
        // 52240 lb of corn at 56 lb/bu
        assertThat(Units.poundsToBushels(52240, d("56"))).isEqualTo(d("932.8571"));
    }

    @Test
    void soybeanBushelsAtSixty() {
        assertThat(Units.poundsToBushels(48000, d("60"))).isEqualTo(d("800.0000"));
    }

    @Test
    void bushelsRoundTripWithinAPound() {
        BigDecimal bushels = Units.poundsToBushels(52240, d("56"));
        assertThat(Units.bushelsToPounds(bushels, d("56"))).isEqualTo(d("52240"));
    }

    @Test
    void zeroStandardTestWeightIsRejected() {
        assertThatThrownBy(() -> Units.poundsToBushels(1000, d("0"))).isInstanceOf(UnitError.class);
    }

    @Test
    void cwtAndTons() {
        assertThat(Units.poundsToCwt(52240)).isEqualTo(d("522.40"));
        assertThat(Units.poundsToTons(52240)).isEqualTo(d("26.120"));
    }

    @Test
    void percentOfUsesBushelPrecisionByDefault() {
        assertThat(Units.percentOf(d("932.8571"), d("1.5"))).isEqualTo(d("13.9929"));
    }
}
